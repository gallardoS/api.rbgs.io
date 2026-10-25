package io.rbgs.api.matches;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchDisputeMessageRepository extends JpaRepository<MatchDisputeMessage, UUID> {
    List<MatchDisputeMessage> findByDisputeIdOrderByRecordedAtDescIdDesc(UUID dispute, Pageable page);
    long countByDisputeIdAndAuthorIdAndRecordedAtAfter(UUID dispute, UUID author, Instant after);
}
