package io.rbgs.api.devices;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

interface DeviceLaunchRepository extends JpaRepository<DeviceLaunchEntity, UUID> {
    long deleteByExpiresAtBefore(java.time.Instant cutoff);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DeviceLaunchEntity> findBySecretHash(String hash);
}
