package io.rbgs.api.matches;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchAssignmentRepository extends JpaRepository<MatchAssignmentEntity, UUID> {
    @Query("select count(a) > 0 from MatchAssignmentEntity a join a.match m where a.accountId=:account "
            + "and m.cancelledAt is null and m.verificationStatus in ('PENDING', 'REVIEW') "
            + "and (m.mode <> io.rbgs.api.matches.MatchMode.CONTROLLED_TEST "
            + "or m.evidencePolicy <> io.rbgs.api.matches.MatchEvidencePolicy.CAPTURE_ONLY "
            + "or m.verificationDeadline is null or m.verificationDeadline > :now)")
    boolean hasBlockingParticipation(@Param("account") UUID account, @Param("now") java.time.Instant now);
    @Query("select a from MatchAssignmentEntity a join fetch a.match where a.match.id=:match and a.accountId=:account")
    Optional<MatchAssignmentEntity> findParticipant(@Param("match") UUID match, @Param("account") UUID account);

    String HISTORY_FILTER = " from MatchAssignmentEntity a join fetch a.match m where a.accountId=:account "
            + "and (m.mode<>io.rbgs.api.matches.MatchMode.CONTROLLED_TEST or :controlledEnabled=true) "
            + "and (:status='ALL' or (:status='CANCELLED' and m.cancelledAt is not null) or (m.cancelledAt is null and (m.verificationStatus=:status or (m.verificationStatus in ('VERIFIED','RESOLVED') and ((:status='DRAW' and m.outcomeKind='DRAW') or (:status='WIN' and m.outcomeKind='WIN' and a.assignedTeam=m.winningTeam) or (:status='LOSS' and m.outcomeKind='WIN' and a.assignedTeam<>m.winningTeam)))))) ";
    @Query("select a" + HISTORY_FILTER + "order by m.createdAt desc, m.id desc")
    List<MatchAssignmentEntity> history(@Param("account") UUID account, @Param("controlledEnabled") boolean enabled,
            @Param("status") String status, Pageable page);
    @Query("select a" + HISTORY_FILTER + "and (m.createdAt<:before or (m.createdAt=:before and m.id<:beforeId)) order by m.createdAt desc, m.id desc")
    List<MatchAssignmentEntity> historyBefore(@Param("account") UUID account, @Param("controlledEnabled") boolean enabled,
            @Param("status") String status, @Param("before") java.time.Instant before, @Param("beforeId") UUID beforeId, Pageable page);
    boolean existsByMatchIdAndAccountId(UUID match, UUID account);
    @Query("select a from MatchAssignmentEntity a join fetch a.match m where a.accountId = :account and (m.mode <> io.rbgs.api.matches.MatchMode.CONTROLLED_TEST or :controlledEnabled = true) order by m.createdAt desc")
    List<MatchAssignmentEntity> listOwn(@Param("account") UUID account, @Param("controlledEnabled") boolean controlledEnabled, Pageable page);

    @Query("select a from MatchAssignmentEntity a join fetch a.match where a.accountId = :account and a.captureId = :capture")
    Optional<MatchAssignmentEntity> findOwn(@Param("account") UUID account, @Param("capture") UUID capture);

    @Query(value = "SELECT a.* FROM rbgs.match_assignments a JOIN rbgs.matches m ON m.id=a.match_id WHERE a.account_id=:account AND a.capture_id=:capture FOR UPDATE OF a,m", nativeQuery = true)
    Optional<MatchAssignmentEntity> lockOwn(@Param("account") UUID account, @Param("capture") UUID capture);
}
