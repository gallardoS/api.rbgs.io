package io.rbgs.api.matchmaking;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
class SoloQueueWorker {
    private final SoloQueueStore store;
    private final SoloQueueRooms rooms;
    private final QueueTicketRepository tickets;
    @Scheduled(fixedDelayString="${rbgs.soloq.cycle-ms:2000}")
    void cycle() {
        // No configuration bypass: admission still requires verified gateway data and a real handoff.
        rooms.expireReal(Instant.now());
        if(!tickets.findBySourceAndStateOrderByEnteredAtAscAccountIdAsc("FOREVER","QUEUED").isEmpty())store.proposeReal();
    }
}
