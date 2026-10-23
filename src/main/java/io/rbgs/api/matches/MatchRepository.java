package io.rbgs.api.matches;

import java.time.Instant;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchRepository extends JpaRepository<MatchEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MatchEntity m where m.id = :id")
    Optional<MatchEntity> lockById(@Param("id") UUID id);

    @Query(value = "SELECT m.* FROM rbgs.matches m WHERE (m.mode<>'CONTROLLED_TEST' OR :controlledEnabled) AND ("
            + "EXISTS (SELECT 1 FROM rbgs.capture_processing_jobs j JOIN rbgs.capture_evidence e ON e.id=j.evidence_id "
            + "JOIN rbgs.match_assignments a ON a.id=e.assignment_id WHERE a.match_id=m.id) "
            + "OR (m.verification_status='PENDING' AND m.verification_deadline<=:now)) "
            + "ORDER BY m.id LIMIT 25 FOR UPDATE OF m SKIP LOCKED", nativeQuery = true)
    List<MatchEntity> lockWork(@Param("now") Instant now, @Param("controlledEnabled") boolean controlledEnabled);
}
