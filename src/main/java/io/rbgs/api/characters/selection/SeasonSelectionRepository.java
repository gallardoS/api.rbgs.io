package io.rbgs.api.characters.selection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SeasonSelectionRepository extends JpaRepository<SeasonSelection, UUID> {
    Optional<SeasonSelection> findByAccountIdAndSeasonId(UUID accountId, UUID seasonId);
}
