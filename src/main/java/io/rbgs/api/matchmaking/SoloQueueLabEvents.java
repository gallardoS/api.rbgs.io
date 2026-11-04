package io.rbgs.api.matchmaking;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Local lab SSE invalidations derived from committed snapshots, with unconditional reconnect resync. */
@Component
@Profile("local & soloq-lab")
@RequiredArgsConstructor
class SoloQueueLabEvents {
    private final SoloQueueLab lab;
    private final io.rbgs.api.identity.AccountRepository accounts;
    private final SessionRepository<? extends Session> sessions;
    private final Set<Client> clients=ConcurrentHashMap.newKeySet();
    private final ExecutorService writers=Executors.newVirtualThreadPerTaskExecutor();
    synchronized SseEmitter open(String session,UUID actor) {
        if (clients.size()>=8) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Simulation stream capacity reached");
        var client=new Client(session,actor); clients.add(client);
        client.emitter.onCompletion(client::remove); client.emitter.onTimeout(client::finish); client.emitter.onError(e->client.remove());
        client.update(); return client.emitter;
    }
    @Scheduled(fixedDelay=1000) void refresh() { clients.forEach(Client::update); }
    @PreDestroy void close() { clients.forEach(Client::finish); writers.shutdownNow(); }
    private class Client {
        final String session;
        final UUID actor;
        final SseEmitter emitter=new SseEmitter(300_000L);
        final AtomicBoolean sending=new AtomicBoolean(),removed=new AtomicBoolean();
        String revision;
        long lastPing;
        Client(String session,UUID actor) { this.session=session; this.actor=actor; }
        void update() {
            if (removed.get() || !sending.compareAndSet(false,true)) return;
            try { writers.submit(()-> {
                try {
                    var current=sessions.findById(session);
                    if (current==null || !actor.equals(current.getAttribute(SoloQueueLabController.ACTOR))
                            || accounts.findById(actor).filter(a->a.getStatus().equals("ACTIVE")).isEmpty()) { finish(); return; }
                    var snapshot=lab.snapshot(actor);
                    if (!actor.equals(snapshot.accountId())) { finish(); return; }
                    if (!Objects.equals(revision,snapshot.revision())) {
                        emitter.send(SseEmitter.event().name(revision==null?"ready":"changed").reconnectTime(2000)
                                .data(Map.of("revision",snapshot.revision())));
                        revision=snapshot.revision(); lastPing=System.currentTimeMillis();
                    } else if (System.currentTimeMillis()-lastPing>=15000) {
                        emitter.send(SseEmitter.event().name("ping").data("{}")); lastPing=System.currentTimeMillis();
                    }
                } catch(IOException | RuntimeException disconnected) { finish(); }
                finally { sending.set(false); }
            }); } catch (RejectedExecutionException stopped) { sending.set(false); finish(); }
        }
        void finish() { remove(); emitter.complete(); }
        void remove() { if (removed.compareAndSet(false,true)) clients.remove(this); }
    }
}
