package io.rbgs.api.matchmaking;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="rbgs.soloq.worker",havingValue="true",matchIfMissing=true)
class SoloQueueWorker {
    private final SoloQueueStore store;
    private final SoloQueueRooms rooms;
    private final QueueTicketRepository tickets;
    @Scheduled(fixedDelayString="${rbgs.soloq.cycle-ms:2000}")
    void cycle() {
        rooms.expireReal(Instant.now());
        if(!tickets.findBySourceAndStateOrderByEnteredAtAscAccountIdAsc("FOREVER","QUEUED").isEmpty())store.proposeReal();
    }
}
