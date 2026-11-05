package io.rbgs.api.matchmaking;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="rbgs.premade.worker",havingValue="true",matchIfMissing=true)
class PremadeQueueWorker {
    private final PremadeQueueStore store;
    private final PremadeQueueEntryRepository entries;
    @Scheduled(fixedDelayString="${rbgs.premade.cycle-ms:2000}") void cycle() {
        if(!entries.findBySourceAndStateOrderByEnteredAtAscIdAsc("FOREVER","QUEUED").isEmpty())store.proposeReal();
    }
}
