package io.rbgs.api.devices;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from DeviceEntity d where d.id = :id")
    Optional<DeviceEntity> lockById(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from DeviceEntity d where d.tokenHash = :hash")
    Optional<DeviceEntity> lockByTokenHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<DeviceEntity> findByIdAndAccountId(UUID id, UUID accountId);
    Optional<DeviceEntity> findByTokenHash(String hash);
    List<DeviceEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    @org.springframework.data.jpa.repository.Query("select d from DeviceEntity d where d.accountId = :accountId and d.removedAt is null and d.revokedAt is null and d.expiresAt > :now order by d.createdAt desc")
    List<DeviceEntity> findActiveByAccountId(UUID accountId, Instant now);
    long countByAccountIdAndRevokedAtIsNullAndExpiresAtAfter(UUID accountId, Instant now);
}
