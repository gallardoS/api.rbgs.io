package io.rbgs.api.matchmaking;

import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface QueueTicketRepository extends JpaRepository<QueueTicket, UUID> {
    @Query(value="SELECT id FROM rbgs.queue_coordinator WHERE id=1 FOR UPDATE", nativeQuery=true)
    int lockCoordinator();
    @Query("select t from QueueTicket t where t.state='QUEUED' and t.queueType='SOLO' order by t.enteredAt,t.accountId")
    List<QueueTicket> waiting();
    @Query("select count(t)>0 from QueueTicket t where t.accountId=:account and t.state in ('QUEUED','PROPOSED')")
    boolean hasReservation(UUID account);
    @Query("select t from QueueTicket t where t.accountId=:account and t.state in ('QUEUED','PROPOSED')")
    Optional<QueueTicket> active(UUID account);
    List<QueueTicket> findByProposalIdOrderByAccountId(UUID proposal);
    Optional<QueueTicket> findByAccountIdAndProposalId(UUID account,UUID proposal);
    List<QueueTicket> findByPremadeEntryIdOrderByAccountId(UUID entry);
    List<QueueTicket> findBySeasonId(UUID season);
    long countByState(String state);
    boolean existsByTestBotTrueAndState(String state);
    long countBySourceAndQueueTypeAndState(String source,String queueType,String state);
    @Query("select t from QueueTicket t where t.accountId=:account and t.proposalId is not null order by t.enteredAt desc,t.id desc")
    List<QueueTicket> recentRooms(UUID account,org.springframework.data.domain.Pageable page);
    List<QueueTicket> findBySourceAndStateOrderByEnteredAtAscAccountIdAsc(String source,String state);
    List<QueueTicket> findByAccountIdAndSourceOrderByEnteredAtDescIdDesc(UUID account,String source,org.springframework.data.domain.Pageable page);
    List<QueueTicket> findByAccountIdAndSourceAndQueueTypeOrderByEnteredAtDescIdDesc(UUID account,String source,String queueType,org.springframework.data.domain.Pageable page);
}
