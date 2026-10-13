package io.rbgs.api.evidence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaptureAssignmentRepository extends JpaRepository<CaptureAssignmentEntity, UUID> {
    @Query("select a from CaptureAssignmentEntity a join fetch a.testMatch m where a.accountId = :account order by m.createdAt desc")
    List<CaptureAssignmentEntity> listOwn(@Param("account") UUID account, Pageable page);

    @Query("select a from CaptureAssignmentEntity a join fetch a.testMatch where a.accountId = :account and a.captureId = :capture")
    Optional<CaptureAssignmentEntity> findOwn(@Param("account") UUID account, @Param("capture") UUID capture);

    @Query(value = "SELECT a.* FROM rbgs.capture_assignments a JOIN rbgs.capture_test_matches m ON m.id=a.match_id WHERE a.account_id=:account AND a.capture_id=:capture FOR UPDATE OF a,m", nativeQuery = true)
    Optional<CaptureAssignmentEntity> lockOwn(@Param("account") UUID account, @Param("capture") UUID capture);
}
