package io.rbgs.api.groups;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Account-scoped invalidations shared by every API instance through PostgreSQL LISTEN. */
@Component
public class GroupEventStreams {
    static final String REMOVED_PREFIX = "removed:";
    private final GroupEventAccess access;
    private final String url, username, password;
    private final int maxConnections;
    private final Map<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore wakeup = new Semaphore(0);
    private volatile boolean running = true, listening;
    private volatile Connection connection;
    private Thread listener;
    private int connectionCount;

    public GroupEventStreams(GroupEventAccess access, @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username, @Value("${spring.datasource.password}") String password,
            @Value("${rbgs.groups.sse.max-connections:1024}") int maxConnections) {
        this.access = access; this.url = url; this.username = username; this.password = password; this.maxConnections = maxConnections;
    }

    synchronized SseEmitter open(UUID account, String sessionId) {
        if (connectionCount >= maxConnections) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lobby stream capacity reached");
        var clients = subscribers.computeIfAbsent(account, ignored -> ConcurrentHashMap.newKeySet());
        if (clients.size() >= 4) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many open lobby streams");
        var client = new Subscriber(account, sessionId);
        clients.add(client); connectionCount++;
        client.emitter.onCompletion(client::remove);
        client.emitter.onTimeout(client::finish);
        client.emitter.onError(error -> client.remove());
        if (listener == null) listener = Thread.ofVirtual().name("lobby-postgres-events").start(this::listen);
        wakeup.release();
        if (listening) client.ready();
        return client.emitter;
    }

    private void listen() {
        while (running) {
            if (subscribers.isEmpty()) {
                try { wakeup.tryAcquire(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { if (!running) return; }
                continue;
            }
            try (var db = DriverManager.getConnection(url, username, password)) {
                connection = db;
                db.setAutoCommit(true);
                try (var command = db.createStatement()) { command.execute("LISTEN rbgs_lobby_changes"); }
                listening = true;
                subscribers.values().forEach(clients -> clients.forEach(Subscriber::ready));
                var pg = db.unwrap(PGConnection.class);
                while (running && !subscribers.isEmpty()) {
                    var notifications = pg.getNotifications(1000);
                    if (notifications == null) continue;
                    for (var notification : notifications) {
                        String payload = notification.getParameter();
                        boolean membershipRemoved = payload.startsWith(REMOVED_PREFIX);
                        UUID account;
                        try { account = UUID.fromString(membershipRemoved ? payload.substring(REMOVED_PREFIX.length()) : payload); }
                        catch (IllegalArgumentException invalid) { continue; }
                        var clients = subscribers.get(account);
                        if (clients != null) clients.forEach(client -> {
                            if (membershipRemoved) client.membershipRemoved(); else client.changed();
                        });
                    }
                }
            } catch (Exception unavailable) {
                // Close streams so EventSource switches to fallback and reconnects. Never log database credentials/payloads.
                subscribers.values().forEach(clients -> clients.forEach(Subscriber::finish));
                if (running) try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            } finally { listening = false; connection = null; }
        }
    }

    @Scheduled(fixedDelay = 15000)
    void heartbeat() { subscribers.values().forEach(clients -> clients.forEach(Subscriber::ping)); }

    synchronized int activeConnections() { return connectionCount; }
    void closeAccount(UUID account) {
        var clients = subscribers.get(account);
        if (clients != null) clients.forEach(Subscriber::finish);
    }

    @PreDestroy
    void shutdown() {
        running = false; wakeup.release();
        subscribers.values().forEach(clients -> clients.forEach(Subscriber::finish));
        if (listener != null) listener.interrupt();
        var db = connection;
        if (db != null) try { db.close(); } catch (Exception ignored) { }
        writers.shutdownNow();
    }

    private class Subscriber {
        final UUID account;
        final String sessionId;
        final SseEmitter emitter = new SseEmitter(300_000L);
        final AtomicBoolean removed = new AtomicBoolean(), sending = new AtomicBoolean(), dirty = new AtomicBoolean();
        final AtomicBoolean ready = new AtomicBoolean(), heartbeat = new AtomicBoolean(), removalNotice = new AtomicBoolean();
        Subscriber(UUID account, String sessionId) { this.account = account; this.sessionId = sessionId; }
        void ready() { ready.set(true); dispatch(); }
        void changed() { dirty.set(true); dispatch(); }
        void ping() { heartbeat.set(true); dispatch(); }
        void membershipRemoved() { removalNotice.set(true); dirty.set(true); dispatch(); }
        void dispatch() {
            if (removed.get() || !sending.compareAndSet(false, true)) return;
            try { writers.submit(this::drain); } catch (RejectedExecutionException stopped) { sending.set(false); finish(); }
        }
        void drain() {
            try {
                while (!removed.get()) {
                    boolean initial = ready.getAndSet(false), changed = dirty.getAndSet(false), ping = heartbeat.getAndSet(false);
                    boolean membershipRemoved = removalNotice.getAndSet(false);
                    if (!initial && !changed && !ping && !membershipRemoved) break;
                    if (ping && !access.valid(account, sessionId)) { finish(); break; }
                    if (initial) emitter.send(SseEmitter.event().name("ready").reconnectTime(3000).data("{}"));
                    if (changed) emitter.send(SseEmitter.event().name("changed").data("{}"));
                    if (membershipRemoved) emitter.send(SseEmitter.event().name("removed").data("{}"));
                    if (ping) emitter.send(SseEmitter.event().name("ping").data("{}"));
                }
            } catch (IOException | RuntimeException disconnected) { finish(); }
            finally {
                sending.set(false);
                if (!removed.get() && (ready.get() || dirty.get() || heartbeat.get() || removalNotice.get())) dispatch();
            }
        }
        void finish() { remove(); emitter.complete(); }
        void remove() {
            if (!removed.compareAndSet(false, true)) return;
            synchronized (GroupEventStreams.this) {
                var clients = subscribers.get(account);
                if (clients != null) { clients.remove(this); if (clients.isEmpty()) subscribers.remove(account, clients); }
                connectionCount--;
            }
        }
    }
}
