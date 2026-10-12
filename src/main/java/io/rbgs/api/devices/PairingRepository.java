package io.rbgs.api.devices;

import java.time.Instant;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PairingRepository extends JpaRepository<PairingEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingEntity p where p.codeHash = :hash")
    Optional<PairingEntity> lockByCode(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingEntity p where p.pollingHash = :hash")
    Optional<PairingEntity> lockByPolling(@Param("hash") String hash);
    long deleteByExpiresAtBefore(Instant cutoff);
}
