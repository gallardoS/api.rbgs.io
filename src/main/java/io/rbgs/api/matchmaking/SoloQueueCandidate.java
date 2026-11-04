package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.MatchRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-owned admission snapshot. Grouping keys require verified game rules. */
public record SoloQueueCandidate(UUID accountId, UUID characterId, UUID seasonId, String region,
        String ruleset, String groupingKey, MatchRole role, boolean captainConsent,
        Instant enteredAt, int mmr, String characterClass, int priorCaptainAssignments,
        Integer gamesPlayed, Integer visibleRating, UUID captainLottery) {
    public SoloQueueCandidate(UUID accountId, UUID characterId, UUID seasonId, String region,
            String ruleset, String groupingKey, MatchRole role, boolean captainConsent,
            Instant enteredAt, int mmr, String characterClass, int priorCaptainAssignments) {
        this(accountId,characterId,seasonId,region,ruleset,groupingKey,role,captainConsent,
                enteredAt,mmr,characterClass,priorCaptainAssignments,0,0,UUID.randomUUID());
    }
    public SoloQueueCandidate {
        gamesPlayed=gamesPlayed==null?0:gamesPlayed;
        visibleRating=visibleRating==null?0:visibleRating;
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(characterId);
        Objects.requireNonNull(seasonId);
        Objects.requireNonNull(role);
        Objects.requireNonNull(enteredAt);
        for (String value : new String[]{region, ruleset, groupingKey, characterClass})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Verified snapshot fields are required");
        if (mmr < 0 || priorCaptainAssignments < 0 || gamesPlayed < 0 || visibleRating < 0)
            throw new IllegalArgumentException("Invalid competitive snapshot");
    }
    public SoloQueueCandidate withCaptainLottery(UUID lottery) {
        return new SoloQueueCandidate(accountId,characterId,seasonId,region,ruleset,groupingKey,role,captainConsent,
                enteredAt,mmr,characterClass,priorCaptainAssignments,gamesPlayed,visibleRating,Objects.requireNonNull(lottery));
    }
}
