package io.rbgs.api.devices;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {
    Optional<DeviceEntity> findByTokenHash(String hash);
    List<DeviceEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    long countByAccountIdAndRevokedAtIsNullAndExpiresAtAfter(UUID accountId, Instant now);
}
