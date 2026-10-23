package io.rbgs.api.evidence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaptureEvidenceRepository extends JpaRepository<CaptureEvidenceEntity, UUID> {
    Optional<CaptureEvidenceEntity> findByAccountIdAndAssignmentIdAndCaptureIdAndRevision(
            UUID account, UUID assignment, UUID capture, int revision);

    @Query("select e from CaptureEvidenceEntity e join fetch e.assignment a where a.match.id = :match order by e.receivedAt, e.id")
    List<CaptureEvidenceEntity> findForMatch(@Param("match") UUID match);
}
