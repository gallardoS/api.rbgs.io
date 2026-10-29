package io.rbgs.api.groups;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface PremadeGroupRepository extends JpaRepository<PremadeGroup, UUID> {
    @Query(value = "select 1 from pg_advisory_xact_lock(82479200608)", nativeQuery = true)
    int lockCodeAllocation();
    boolean existsByJoinCodeHash(String hash);
    @Query("select g.id from PremadeGroup g where g.joinCodeHash = :hash")
    Optional<UUID> findIdByJoinCodeHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PremadeGroup g where g.id = :id")
    Optional<PremadeGroup> lockById(UUID id);
}
