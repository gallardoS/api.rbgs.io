package io.rbgs.api.matches;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchResultEventRepository extends JpaRepository<MatchResultEvent, UUID> {
    List<MatchResultEvent> findByMatchIdOrderByRecordedAtAscIdAsc(UUID match);
}
