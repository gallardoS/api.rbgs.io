package io.rbgs.api.characters.selection;
import java.util.UUID;
import io.rbgs.api.seasons.RatingSubjectType;
public record SelectionView(UUID characterId, String name, String realm,
        CharacterIdentity.Source source, RatingSubjectType ratingSubjectType, UUID ratingSubjectId,
        MatchRole role, boolean captainConsent, long version, boolean validForSeason) { }
