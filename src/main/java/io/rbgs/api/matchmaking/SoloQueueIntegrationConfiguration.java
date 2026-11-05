package io.rbgs.api.matchmaking;

import java.util.*;
import java.time.Instant;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.seasons.SeasonEntity;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
public class SoloQueueIntegrationConfiguration {
    @Bean @ConditionalOnMissingBean
    SoloQueueCompetitiveData confirmedCompetitiveData(ConfirmedRatingStore ratings) {
        return (season,type,subject)->ratings.confirmed(season,ConfirmedRatingStore.Queue.SOLO,type,subject)
                .map(s->new SoloQueueCompetitiveData.Snapshot(s.seasonId(),s.subjectType(),s.subjectId(),s.mmr(),s.visibleRating(),s.gamesPlayed(),s.priorCaptainAssignments()));
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
