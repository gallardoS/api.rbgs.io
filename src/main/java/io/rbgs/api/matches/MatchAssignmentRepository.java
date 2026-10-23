package io.rbgs.api.matches;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchAssignmentRepository extends JpaRepository<MatchAssignmentEntity, UUID> {
    boolean existsByMatchIdAndAccountId(UUID match, UUID account);
    @Query("select a from MatchAssignmentEntity a join fetch a.match m where a.accountId = :account and (m.mode <> io.rbgs.api.matches.MatchMode.CONTROLLED_TEST or :controlledEnabled = true) order by m.createdAt desc")
    List<MatchAssignmentEntity> listOwn(@Param("account") UUID account, @Param("controlledEnabled") boolean controlledEnabled, Pageable page);

    @Query("select a from MatchAssignmentEntity a join fetch a.match where a.accountId = :account and a.captureId = :capture")
    Optional<MatchAssignmentEntity> findOwn(@Param("account") UUID account, @Param("capture") UUID capture);

    @Query(value = "SELECT a.* FROM rbgs.match_assignments a JOIN rbgs.matches m ON m.id=a.match_id WHERE a.account_id=:account AND a.capture_id=:capture FOR UPDATE OF a,m", nativeQuery = true)
    Optional<MatchAssignmentEntity> lockOwn(@Param("account") UUID account, @Param("capture") UUID capture);
}
