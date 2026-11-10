package io.rbgs.api.matchmaking;

import io.rbgs.api.seasons.RatingSubjectType;
import java.util.*;

/** Confirmed SoloQ values only; an unavailable source is not an initial-rating record. */
public interface SoloQueueCompetitiveData {
    record Snapshot(UUID seasonId,RatingSubjectType subjectType,UUID subjectId,int mmr,int visibleRating,
            int gamesPlayed,int priorCaptainAssignments) { }
    Optional<Snapshot> confirmed(UUID season,RatingSubjectType type,UUID subject);

    default Map<UUID, Snapshot> confirmedForRoster(UUID season, RatingSubjectType type, Set<UUID> subjects) {
        Map<UUID, Snapshot> result = new HashMap<>();
        for (UUID subject : subjects) confirmed(season, type, subject).ifPresent(value -> result.put(subject, value));
        return result;
    }
}
