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
    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void removeExpired() { pairings.deleteByExpiresAtBefore(Instant.now().minusSeconds(86400)); }
}
