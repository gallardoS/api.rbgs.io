package io.rbgs.api.characters.selection;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class SelectionSettings {
    private final UUID seasonId;
    public SelectionSettings(@Value("${rbgs.play.season-id:}") String seasonId) {
        this.seasonId = seasonId.isBlank() ? null : UUID.fromString(seasonId);
    }
    public UUID seasonId() { return seasonId; }
}
