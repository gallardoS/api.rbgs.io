package io.rbgs.api.matches;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchDisputeRepository extends JpaRepository<MatchDispute, UUID> {
    Optional<MatchDispute> findByMatchIdAndAccountId(UUID match, UUID account);
    List<MatchDispute> findByMatchIdOrderByOpenedAtAscIdAsc(UUID match);
}
