package io.rbgs.api.matchmaking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
public class SoloQueueEvents {
    static final String REMOVED_PREFIX = "removed:";
    private final SoloQueueEventAccess access;
    private final String url, username, password;
    private final int maxConnections;
    private final long timeoutMs;
    private final Counter opened, invalidations, dispatchFailures, listenerFailures;
    private final Map<CloseReason, Counter> closed = new EnumMap<>(CloseReason.class);
    private final Map<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore wakeup = new Semaphore(0);
    private volatile boolean running = true, listening;
    private volatile Connection connection;
    private Thread listener;
    private int connectionCount;

    private enum CloseReason {
        COMPLETED, TIMEOUT, TRANSPORT, UNAUTHORIZED, LISTENER, DISPATCH, ACCOUNT, SHUTDOWN
    }

    public SoloQueueEvents(SoloQueueEventAccess access, MeterRegistry metrics, @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username, @Value("${spring.datasource.password}") String password,
            @Value("${rbgs.soloq.sse.max-connections:1024}") int maxConnections,
            @Value("${rbgs.soloq.sse.timeout-ms:0}") long timeoutMs) {
        this.access = access; this.url = url; this.username = username; this.password = password; this.maxConnections = maxConnections;
        this.timeoutMs = timeoutMs;
        metrics.gauge("rbgs.queue.sse.active", this, SoloQueueEvents::activeConnections);
        metrics.gauge("rbgs.queue.sse.accounts", this, SoloQueueEvents::activeAccounts);
        opened = metrics.counter("rbgs.queue.sse.opened");
        invalidations = metrics.counter("rbgs.queue.sse.invalidations");
        dispatchFailures = metrics.counter("rbgs.queue.sse.dispatch.failures");
        listenerFailures = metrics.counter("rbgs.queue.sse.listener.failures");
        for (var reason : CloseReason.values()) {
            closed.put(reason, metrics.counter("rbgs.queue.sse.closed", "reason", reason.name().toLowerCase(Locale.ROOT)));
        }
    }

    synchronized SseEmitter open(UUID account, String sessionId) {
        if (connectionCount >= maxConnections) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Queue stream capacity reached");
        var clients = subscribers.computeIfAbsent(account, ignored -> ConcurrentHashMap.newKeySet());
        if (clients.size() >= 4) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many open queue streams");
        var client = new Subscriber(account, sessionId);
        clients.add(client); connectionCount++;
        opened.increment();
        client.emitter.onCompletion(() -> client.remove(CloseReason.COMPLETED));
        client.emitter.onTimeout(() -> client.finish(CloseReason.TIMEOUT));
        client.emitter.onError(error -> client.remove(CloseReason.TRANSPORT));
        if (listener == null) listener = Thread.ofVirtual().name("soloq-postgres-events").start(this::listen);
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
                try (var command = db.createStatement()) { command.execute("LISTEN rbgs_solo_queue"); }
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
                if (running) listenerFailures.increment();
                subscribers.values().forEach(clients -> clients.forEach(client -> client.finish(CloseReason.LISTENER)));
                if (running) try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            } finally { listening = false; connection = null; }
        }
    }

    @Scheduled(fixedDelay = 15000)
    void heartbeat() { subscribers.values().forEach(clients -> clients.forEach(Subscriber::ping)); }

    // A flushed comment detects disconnected sockets without triggering a browser refresh or an auth lookup.
    @Scheduled(fixedDelay = 5000)
    void probeTransport() { subscribers.values().forEach(clients -> clients.forEach(Subscriber::probe)); }

    synchronized int activeConnections() { return connectionCount; }
    synchronized int activeAccounts() { return subscribers.size(); }
    void closeAccount(UUID account) {
        var clients = subscribers.get(account);
        if (clients != null) clients.forEach(client -> client.finish(CloseReason.ACCOUNT));
    }

    @PreDestroy
    void shutdown() {
        running = false; wakeup.release();
        subscribers.values().forEach(clients -> clients.forEach(client -> client.finish(CloseReason.SHUTDOWN)));
        if (listener != null) listener.interrupt();
        var db = connection;
        if (db != null) try { db.close(); } catch (Exception ignored) { }
        writers.shutdownNow();
    }

    private class Subscriber {
        final UUID account;
        final String sessionId;
        final SseEmitter emitter = new SseEmitter(timeoutMs);
        final AtomicBoolean removed = new AtomicBoolean(), sending = new AtomicBoolean(), dirty = new AtomicBoolean();
        final AtomicBoolean ready = new AtomicBoolean(), heartbeat = new AtomicBoolean(), removalNotice = new AtomicBoolean();
        final AtomicBoolean transportProbe = new AtomicBoolean();
        Subscriber(UUID account, String sessionId) { this.account = account; this.sessionId = sessionId; }
        void ready() { ready.set(true); dispatch(); }
        void changed() { dirty.set(true); dispatch(); }
        void ping() { heartbeat.set(true); dispatch(); }
        void probe() { transportProbe.set(true); dispatch(); }
        void membershipRemoved() { removalNotice.set(true); dirty.set(true); dispatch(); }
        void dispatch() {
            if (removed.get() || !sending.compareAndSet(false, true)) return;
            try { writers.submit(this::drain); } catch (RejectedExecutionException stopped) {
                sending.set(false);
                finish(CloseReason.SHUTDOWN);
            }
        }
        void drain() {
            try {
                while (!removed.get()) {
                    boolean initial = ready.getAndSet(false), changed = dirty.getAndSet(false), ping = heartbeat.getAndSet(false);
                    boolean membershipRemoved = removalNotice.getAndSet(false);
                    boolean probe = transportProbe.getAndSet(false);
                    if (!initial && !changed && !ping && !membershipRemoved) {
                        if (!probe) break;
                        emitter.send(SseEmitter.event().comment("keepalive"));
                        continue;
                    }
                    if (!access.valid(account, sessionId)) { finish(CloseReason.UNAUTHORIZED); break; }
                    if (initial) emitter.send(SseEmitter.event().name("ready").reconnectTime(3000).data("{}"));
                    if (changed) {
                        emitter.send(SseEmitter.event().name("changed").data("{}"));
                        invalidations.increment();
                    }
                    if (membershipRemoved) emitter.send(SseEmitter.event().name("removed").data("{}"));
                    if (ping) emitter.send(SseEmitter.event().name("ping").data("{}"));
                }
            } catch (IOException disconnected) {
                dispatchFailures.increment();
                remove(CloseReason.TRANSPORT);
            } catch (RuntimeException failed) {
                dispatchFailures.increment();
                finish(CloseReason.DISPATCH);
            }
            finally {
                sending.set(false);
                if (!removed.get() && (ready.get() || dirty.get() || heartbeat.get() || removalNotice.get() || transportProbe.get())) dispatch();
            }
        }
        void finish(CloseReason reason) {
            if (remove(reason)) emitter.complete();
        }
        boolean remove(CloseReason reason) {
            if (!removed.compareAndSet(false, true)) return false;
            synchronized (SoloQueueEvents.this) {
                var clients = subscribers.get(account);
                if (clients != null) { clients.remove(this); if (clients.isEmpty()) subscribers.remove(account, clients); }
                connectionCount--;
            }
            closed.get(reason).increment();
            return true;
        }
    }
}
