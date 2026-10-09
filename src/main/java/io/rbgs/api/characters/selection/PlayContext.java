package io.rbgs.api.characters.selection;
import java.util.UUID;
import io.rbgs.api.seasons.RatingSubjectType;
import io.rbgs.api.seasons.SeasonKind;
public record PlayContext(Season season, SelectionView selection, String ownershipVerification, boolean matchmakingAvailable) {
    public record Season(UUID id, String name, SeasonKind kind, RatingSubjectType ratingSubjectType, long version) { }
}
