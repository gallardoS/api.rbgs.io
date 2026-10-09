package io.rbgs.api.seasons;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SeasonIdentityTests {
    @Autowired SeasonRepository seasons;
    @Autowired JdbcTemplate database;

    @Test
    void bothModesAndKindsPersistWithoutSharingSeasonIdentity() {
        var beta = seasons.saveAndFlush(new SeasonEntity("Fixture beta", SeasonKind.BETA, RatingSubjectType.ACCOUNT));
        var launch = seasons.saveAndFlush(new SeasonEntity("Fixture launch", SeasonKind.PUBLIC, RatingSubjectType.CHARACTER));
        try {
            assertNotEquals(beta.getId(), launch.getId());
            assertEquals(RatingSubjectType.ACCOUNT, seasons.findById(beta.getId()).orElseThrow().getRatingSubjectType());
            assertEquals(RatingSubjectType.CHARACTER, seasons.findById(launch.getId()).orElseThrow().getRatingSubjectType());
            assertNull(beta.getStartedAt());
            assertNull(launch.getStartedAt());
        } finally {
            seasons.deleteAllById(List.of(beta.getId(), launch.getId()));
        }
    }

    @Test
    void configurationCanChangeBeforeStartButIsFrozenAfterStart() {
        var season = new SeasonEntity("Fixture identity", SeasonKind.BETA, RatingSubjectType.ACCOUNT);
        season.configureRatingSubjectType(RatingSubjectType.CHARACTER);
        var startedAt = Instant.parse("2026-10-09T12:00:00Z");
        season.start(startedAt);
        season = seasons.saveAndFlush(season);
        var stored = season;
        try {
            assertThrows(IllegalStateException.class, () -> stored.configureRatingSubjectType(RatingSubjectType.ACCOUNT));
            assertThrows(IllegalStateException.class, () -> stored.start(startedAt.plusSeconds(1)));
            assertEquals(startedAt, seasons.findById(season.getId()).orElseThrow().getStartedAt());
            for (String change : List.of("rating_subject_type = 'ACCOUNT'", "kind = 'PUBLIC'", "started_at = NULL",
                    "started_at = started_at + INTERVAL '1 second'")) {
                assertThrows(DataIntegrityViolationException.class, () -> database.update(
                        "UPDATE rbgs.seasons SET " + change + " WHERE id = ?", stored.getId()));
            }
        } finally {
            seasons.deleteById(season.getId());
        }
    }

    @Test
    void staleConfigurationCannotOverwriteAnotherOperatorsStart() {
        var original = seasons.saveAndFlush(new SeasonEntity("Fixture race", SeasonKind.BETA, RatingSubjectType.ACCOUNT));
        try {
            var starting = seasons.findById(original.getId()).orElseThrow();
            var stale = seasons.findById(original.getId()).orElseThrow();
            starting.start(Instant.parse("2026-10-09T12:00:00Z"));
            seasons.saveAndFlush(starting);
            stale.configureRatingSubjectType(RatingSubjectType.CHARACTER);
            assertThrows(OptimisticLockingFailureException.class, () -> seasons.saveAndFlush(stale));
            assertEquals(RatingSubjectType.ACCOUNT, seasons.findById(original.getId()).orElseThrow().getRatingSubjectType());
        } finally {
            seasons.deleteById(original.getId());
        }
    }

    @Test
    void seasonModeAndKindMustBeExplicit() {
        assertThrows(NullPointerException.class, () -> new SeasonEntity("Fixture", SeasonKind.BETA, null));
        assertThrows(NullPointerException.class, () -> new SeasonEntity("Fixture", null, RatingSubjectType.ACCOUNT));
        assertThrows(IllegalArgumentException.class, () -> new SeasonEntity(" ", SeasonKind.BETA, RatingSubjectType.ACCOUNT));
    }
}
