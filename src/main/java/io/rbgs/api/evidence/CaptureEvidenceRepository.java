package io.rbgs.api.evidence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaptureEvidenceRepository extends JpaRepository<CaptureEvidenceEntity, UUID> {
    List<CaptureEvidenceEntity> findByAccountIdAndAssignmentIdAndCaptureIdOrderByRevisionDesc(
            UUID account, UUID assignment, UUID capture, org.springframework.data.domain.Pageable page);
    Optional<CaptureEvidenceEntity> findByAccountIdAndAssignmentIdAndCaptureIdAndRevision(
            UUID account, UUID assignment, UUID capture, int revision);

    @Query("select e from CaptureEvidenceEntity e join fetch e.assignment a where a.match.id = :match order by e.receivedAt, e.id")
    List<CaptureEvidenceEntity> findForMatch(@Param("match") UUID match);

    @Query("""
            select e from CaptureEvidenceEntity e
            where e.accountId = :account and e.assignment.id in (:assignments)
              and e.captureId = e.assignment.captureId
              and e.revision = (select max(r.revision) from CaptureEvidenceEntity r
                  where r.accountId = e.accountId and r.assignment.id = e.assignment.id and r.captureId = e.captureId)
            """)
    List<CaptureEvidenceEntity> findLatestForAssignments(@Param("account") UUID account, @Param("assignments") Collection<UUID> assignments);
}
