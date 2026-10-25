package io.rbgs.api.matches;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchHistoryReadRepository extends JpaRepository<MatchHistoryRead, UUID> {}
