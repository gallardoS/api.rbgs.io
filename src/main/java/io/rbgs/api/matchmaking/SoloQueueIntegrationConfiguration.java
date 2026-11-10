package io.rbgs.api.matchmaking;

import java.util.*;
import java.time.Instant;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.seasons.SeasonEntity;
import io.rbgs.api.seasons.RatingSubjectType;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
public class SoloQueueIntegrationConfiguration {
    @Bean @ConditionalOnMissingBean
    SoloQueueCompetitiveData confirmedCompetitiveData(ConfirmedRatingStore ratings) {
        return new SoloQueueCompetitiveData() {
            public Optional<Snapshot> confirmed(UUID season, RatingSubjectType type, UUID subject) {
                return ratings.confirmed(season, ConfirmedRatingStore.Queue.SOLO, type, subject).map(this::snapshot);
            }

            @Override
            public Map<UUID, Snapshot> confirmedForRoster(UUID season, RatingSubjectType type, Set<UUID> subjects) {
                Map<UUID, Snapshot> result = new HashMap<>();
                ratings.confirmedForRoster(season, ConfirmedRatingStore.Queue.SOLO, type, subjects)
                        .forEach((id, value) -> result.put(id, snapshot(value)));
                return result;
            }

            private Snapshot snapshot(ConfirmedRatingStore.Snapshot value) {
                return new Snapshot(value.seasonId(), value.subjectType(), value.subjectId(), value.mmr(),
                        value.visibleRating(), value.gamesPlayed(), value.priorCaptainAssignments());
            }
        };
    }
    @Bean @ConditionalOnMissingBean
    SoloQueueGameGateway unavailableGameGateway() {
        return new SoloQueueGameGateway() {
            public Optional<VerifiedPlayer> inspect(UUID account,SeasonEntity season,SeasonSelection selection) { return Optional.empty(); }
            public boolean canOppose(String ruleset,String first,String second) { return false; }
            public boolean handoffAvailable() { return false; }
            public UUID createMatch(SoloQueueMatcher.Proposal proposal,Instant now) { throw new IllegalStateException("Verified game handoff is unavailable"); }
        };
    }
}
