package io.rbgs.api.matches;

import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface MatchRoomRepository extends JpaRepository<MatchRoomEntity,UUID> {
    List<MatchRoomEntity> findByStatusIn(Collection<String> statuses);
    List<MatchRoomEntity> findBySeasonId(UUID season);
    List<MatchRoomEntity> findBySourceAndStatusIn(String source,Collection<String> statuses);
    @Query("select count(r) from MatchRoomEntity r where r.seasonId=:season and (r.captainA=:account or r.captainB=:account)")
    int captainAssignments(UUID season,UUID account);
}
