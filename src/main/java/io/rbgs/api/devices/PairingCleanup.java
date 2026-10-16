package io.rbgs.api.devices;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PairingCleanup {
    private final PairingRepository pairings;
    private final DeviceLaunchRepository launches;
    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void removeExpired() {
        var cutoff=Instant.now().minusSeconds(86400);
        launches.deleteByExpiresAtBefore(cutoff);
        pairings.deleteByExpiresAtBefore(cutoff);
    }
}
