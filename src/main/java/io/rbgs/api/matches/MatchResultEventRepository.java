package io.rbgs.api.matches;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchResultEventRepository extends JpaRepository<MatchResultEvent, UUID> {
    List<MatchResultEvent> findByMatchIdOrderByRecordedAtAscIdAsc(UUID match);
    List<MatchResultEvent> findByMatchIdOrderByRecordedAtDescIdDesc(UUID match, org.springframework.data.domain.Pageable page);
}
