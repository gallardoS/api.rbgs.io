package io.rbgs.api.matchmaking;

import io.rbgs.api.seasons.RatingSubjectType;
import java.util.*;

/** Confirmed SoloQ values only; an unavailable source is not an initial-rating record. */
public interface SoloQueueCompetitiveData {
    record Snapshot(UUID seasonId,RatingSubjectType subjectType,UUID subjectId,int mmr,int visibleRating,
            int gamesPlayed,int priorCaptainAssignments) { }
    Optional<Snapshot> confirmed(UUID season,RatingSubjectType type,UUID subject);
}
