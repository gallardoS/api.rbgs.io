package io.rbgs.api.matches;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface MatchRoomRepository extends JpaRepository<MatchRoomEntity,UUID> {
    List<MatchRoomEntity> findByStatusIn(Collection<String> statuses);
    List<MatchRoomEntity> findBySeasonId(UUID season);
    List<MatchRoomEntity> findBySourceAndStatusIn(String source,Collection<String> statuses);
    @Query("select count(r) from MatchRoomEntity r where r.seasonId=:season and (r.captainA=:account or r.captainB=:account)")
    int captainAssignments(UUID season,UUID account);

    @Query("""
            select r from MatchRoomEntity r join QueueTicket t on t.proposalId = r.id
            where t.accountId = :account and t.seasonId = :season and t.queueType = 'PREMADE'
              and t.source = 'SIMULATION' and r.source = 'SIMULATION' and r.seasonId = :season
              and r.status in ('AWAITING_EVIDENCE','UNDER_REVIEW','RESOLVED')
            order by r.preparationDeadline desc, r.id desc
            """)
    List<MatchRoomEntity> recentFinishedSimulations(UUID account,UUID season,Pageable page);
}
