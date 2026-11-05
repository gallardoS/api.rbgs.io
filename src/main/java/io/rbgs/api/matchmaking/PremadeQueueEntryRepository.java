package io.rbgs.api.matchmaking;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PremadeQueueEntryRepository extends JpaRepository<PremadeQueueEntry, UUID> {
    List<PremadeQueueEntry> findByStateOrderByEnteredAtAscIdAsc(String state);
    List<PremadeQueueEntry> findBySourceAndStateOrderByEnteredAtAscIdAsc(String source,String state);
    Optional<PremadeQueueEntry> findByGroupIdAndState(UUID groupId, String state);
    @org.springframework.data.jpa.repository.Query("select e from PremadeQueueEntry e where e.groupId=:group and "
            +"(e.state='QUEUED' or exists (select t.id from QueueTicket t where t.premadeEntryId=e.id and t.state='PROPOSED'))")
    List<PremadeQueueEntry> reservedForGroup(UUID group);
}
