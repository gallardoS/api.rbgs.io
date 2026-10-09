package io.rbgs.api.seasons;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonRepository extends JpaRepository<SeasonEntity, UUID> {
}
