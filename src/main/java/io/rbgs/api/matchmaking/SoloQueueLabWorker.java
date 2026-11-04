package io.rbgs.api.matchmaking;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local & soloq-lab")
@ConditionalOnProperty(name="rbgs.soloq-lab.worker",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
class SoloQueueLabWorker {
    private final SoloQueueLab lab;
    @Scheduled(fixedDelayString="${rbgs.soloq-lab.cycle-ms:2000}")
    void cycle() { lab.cycle(); }
}
