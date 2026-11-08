package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.characters.selection.CharacterIdentityRepository;
import io.rbgs.api.characters.selection.MatchRole;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.matchmaking.ConfirmedRatingStore.Queue;
import io.rbgs.api.matches.*;
import io.rbgs.api.ratings.RatingResults;
import io.rbgs.api.seasons.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "rbgs.rating.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class RatingResultsIntegrationTests {
    private static final String ISSUER = "https://rating-fixtures.example";
    @Autowired AccountService accounts;
    @Autowired CharacterIdentityRepository characters;
    @Autowired SeasonSelectionRepository selections;
    @Autowired SeasonRepository seasons;
    @Autowired QueueProposalRepository proposals;
    @Autowired MatchRoomRepository rooms;
    @Autowired MatchRepository matches;
    @Autowired MatchAssignmentRepository assignments;
    @Autowired MatchResultService results;
    @Autowired ConfirmedRatingStore confirmed;
    @Autowired RatingResults ratings;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;
    private UUID season;

    @BeforeEach void setUp() {
        var fixture = new SeasonEntity("Rating fixture", SeasonKind.BETA, RatingSubjectType.ACCOUNT);
        fixture.start(Instant.now().minusSeconds(60));
        season = seasons.saveAndFlush(fixture).getId();
    }

    @Test void lateResultAndCorrectionReplayInCreationOrderWithoutDoubleApplying() throws Exception {
        var a = team();
        var b = team();
        var c = team();
        var d = team();
        var first = freeze(Queue.SOLO, a, b);
        var second = freeze(Queue.SOLO, a, c);
        var third = freeze(Queue.SOLO, b, d);
        var premade = freeze(Queue.PREMADE, a, b);
        assertEquals(List.of(1L, 2L, 3L, 1L), List.of(position(first), position(second), position(third), position(premade)));
        String originalSnapshot = snapshot(second);

        confirm(second, "A");
        confirm(third, "B");
        assertEquals(1484, rating(Queue.SOLO, c).mmr());
        confirm(first, "A");
        var winner = rating(Queue.SOLO, a);
        assertEquals(1531, winner.mmr());
        assertEquals(64, winner.visibleRating());
        assertEquals(2, winner.gamesPlayed());
        assertEquals(2, winner.priorCaptainAssignments());
        assertEquals(1485, rating(Queue.SOLO, c).mmr());
        assertEquals(1515, rating(Queue.SOLO, d).mmr());
        assertTrue(ratings.applied(first));

        confirm(first, "A");
        assertEquals(1, count("rating_result_revisions", "match_id", first.getId()));
        assertEquals(60, count("rating_movements", "replay_match_id", first.getId()));
        assertEquals(winner, rating(Queue.SOLO, a));

        confirm(first, "B");
        assertEquals(1501, rating(Queue.SOLO, a).mmr());
        assertEquals(1499, rating(Queue.SOLO, b).mmr());
        assertEquals(1483, rating(Queue.SOLO, c).mmr());
        assertEquals(1517, rating(Queue.SOLO, d).mmr());
        assertEquals(120, count("rating_movements", "replay_match_id", first.getId()));
        assertEquals(originalSnapshot, snapshot(second));

        confirm(premade, "B");
        assertEquals(1484, rating(Queue.PREMADE, a).mmr());
        assertEquals(1501, rating(Queue.SOLO, a).mmr());
        profile(a.getFirst().characterId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.solo.rating").value(rating(Queue.SOLO, a).visibleRating()))
                .andExpect(jsonPath("$.solo.games").value(2))
                .andExpect(jsonPath("$.solo.wins").value(1))
                .andExpect(jsonPath("$.solo.losses").value(1))
                .andExpect(jsonPath("$.solo.initialRating").value(0))
                .andExpect(jsonPath("$.solo.progression[*].rating").value(org.hamcrest.Matchers.contains(0, 32)))
                .andExpect(jsonPath("$.solo.peak").value(32))
                .andExpect(jsonPath("$.premade.rating").value(0))
                .andExpect(jsonPath("$.premade.games").value(1))
                .andExpect(jsonPath("$.premade.progression[0].rating").value(0))
                .andExpect(jsonPath("$.matches[?(@.queue == 'SOLO')].outcome")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("WIN", "LOSS")))
                .andExpect(jsonPath("$.matches.length()").value(3));
    }

    @Test void leaderboardIncludesOnlyAppliedResultsAndUpdatesAfterModeratorVoid() throws Exception {
        var a = team();
        var b = team();
        var match = freeze(Queue.PREMADE, a, b);
        UUID player = a.getFirst().characterId();
        standings(Queue.PREMADE).andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.totalPages").value(0));
        profile(player).andExpect(status().isNotFound());
        confirm(match, "A");
        db.update("""
                INSERT INTO rbgs.play_characters(account_id, namespace, wow_account_id, realm_id, character_id, region, name, realm, character_profile)
                VALUES (?, 'profile-forever-eu', 1, 1, 1, 'EU', 'Player0', 'Fixture',
                    '{"playable_race":{"name":"Orc"},"gender":{"type":"FEMALE"}}'::jsonb),
                       (?, 'profile-classicann-eu', 2, 1, 2, 'EU', 'Player0', 'Fixture',
                    '{"playable_race":{"name":"Human"},"gender":{"type":"MALE"}}'::jsonb)
                """, a.getFirst().accountId(), b.getFirst().accountId());
        standings(Queue.PREMADE)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.entries.length()").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.entries[0].rating").value(32))
                .andExpect(jsonPath("$.entries[0].wins").value(1))
                .andExpect(jsonPath("$.entries[0].mmr").doesNotExist())
                .andExpect(jsonPath("$.entries[0].accountId").doesNotExist())
                .andExpect(jsonPath("$.entries[10].rating").value(0))
                .andExpect(jsonPath("$.entries[10].losses").value(1));
        standings(Queue.SOLO).andExpect(jsonPath("$.entries").isEmpty());
        mvc.perform(get("/api/v1/local/leaderboard").param("season", season.toString()).param("queue", "PREMADE")
                .param("search", "player0").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].name").value("Player0"))
                .andExpect(jsonPath("$.entries[0].uniqueName").value(false))
                .andExpect(jsonPath("$.entries[0].profileRef").value(player.toString()))
                .andExpect(jsonPath("$.entries[0].characterClass").value("WARRIOR"))
                .andExpect(jsonPath("$.entries[0].race").value("Orc"))
                .andExpect(jsonPath("$.entries[0].gender").value("FEMALE"))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.nextOffset").value(1));
        mvc.perform(get("/api/v1/local/leaderboard/profile")
                .param("region", "eu").param("realm", "fixture").param("name", "player0"))
                .andExpect(status().isNotFound());
        db.update("UPDATE rbgs.character_identities SET name = ? WHERE id = ?", "Player Uno", a.get(1).characterId());
        mvc.perform(get("/api/v1/local/leaderboard/profile")
                .param("region", "eu").param("realm", "fixture").param("name", "player uno"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season.id").value(season.toString()))
                .andExpect(jsonPath("$.name").value("Player Uno"));
        UUID otherCharacter = characters.saveAndFlush(
                CharacterIdentity.declared(a.getFirst().accountId(), "UnratedAlt", "Fixture")).getId();
        for (UUID reference : List.of(player, otherCharacter)) {
            profile(reference).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.season.subjectType").value("ACCOUNT"))
                    .andExpect(jsonPath("$.name").value("Player0"))
                    .andExpect(jsonPath("$.appearance.characterClass").value("WARRIOR"))
                    .andExpect(jsonPath("$.appearance.region").value("EU"))
                    .andExpect(jsonPath("$.appearance.race").value("Orc"))
                    .andExpect(jsonPath("$.accountId").doesNotExist())
                    .andExpect(jsonPath("$.solo").doesNotExist())
                    .andExpect(jsonPath("$.premade.mmr").doesNotExist())
                    .andExpect(jsonPath("$.premade.rating").value(32))
                    .andExpect(jsonPath("$.premade.games").value(1))
                    .andExpect(jsonPath("$.matches[0].character").value("Player0"))
                    .andExpect(jsonPath("$.matches[0].outcome").value("WIN"))
                    .andExpect(jsonPath("$.matches[0].rating").value(32))
                    .andExpect(jsonPath("$.matches[0].matchId").doesNotExist());
        }
        mvc.perform(get("/api/v1/local/leaderboard").param("season", season.toString()).param("queue", "PREMADE")
                .param("search", "player0").param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].rating").value(0))
                .andExpect(jsonPath("$.entries[0].race").doesNotExist())
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.nextOffset").doesNotExist())
                .andExpect(jsonPath("$.entries[0].rank").value(org.hamcrest.Matchers.greaterThan(10)));
        mvc.perform(get("/api/v1/local/leaderboard").param("season", season.toString()).param("queue", "PREMADE")
                .param("search", "player0").param("limit", "1").param("offset", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/local/leaderboard").param("limit", "51")).andExpect(status().isBadRequest());
        String subject = "moderator-" + UUID.randomUUID();
        var moderator = accounts.upsert(ISSUER, subject, "Rating moderator");
        db.update("UPDATE rbgs.accounts SET account_role='MODERATOR' WHERE id=?", moderator.id());
        entityManager.clear();
        var now = Instant.now();
        var token = new OidcIdToken("fixture", now, now.plusSeconds(60), Map.of("iss", ISSUER, "sub", subject));
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_MODERATOR"));
        var auth = new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, token), authorities, "battle-net");

        var result = results.decide(auth, match.getId(), new MatchResultService.ModeratorDecision(
                match.getResultVersion(), "VOID", null, "Fixture result voided", List.of()));
        assertEquals("VOIDED", result.status());
        assertFalse(result.ratingApplied());
        assertEquals(1500, rating(Queue.PREMADE, a).mmr());
        assertEquals(0, rating(Queue.PREMADE, a).gamesPlayed());
        assertEquals(0, rating(Queue.PREMADE, a).priorCaptainAssignments());
        assertEquals(20, count("rating_movements", "replay_match_id", match.getId()));
        assertEquals(2, count("rating_result_revisions", "match_id", match.getId()));
        standings(Queue.PREMADE).andExpect(jsonPath("$.entries").isEmpty());
        profile(player).andExpect(status().isNotFound());
    }

    @Test void floorsAnIndividualMmrAndLeavesPendingAndControlledResultsUnrated() {
        var a = team();
        var b = team();
        db.update("UPDATE rbgs.competitive_ratings SET mmr=1700 WHERE season_id=? AND queue_type='SOLO'", season);
        for (var player : b) setMmr(player.accountId(), 1530);
        setMmr(a.getFirst().accountId(), 5);
        var match = freeze(Queue.SOLO, a, b);
        ratings.synchronize(match);
        assertEquals(0, count("rating_result_revisions", "match_id", match.getId()));
        var controlled = createMatch(a.getFirst().accountId(), MatchMode.CONTROLLED_TEST);
        confirm(controlled, "B");
        assertFalse(ratings.applied(controlled));
        assertEquals(5, rating(Queue.SOLO, a).mmr());

        confirm(match, "B");
        assertEquals(0, rating(Queue.SOLO, a).mmr());
        assertEquals(1546, rating(Queue.SOLO, b).mmr());
        assertEquals(1684, confirmed.confirmed(season, Queue.SOLO, RatingSubjectType.ACCOUNT,
                a.get(1).accountId()).orElseThrow().mmr());
    }

    private List<SoloQueueCandidate> team() {
        return IntStream.range(0, 10).mapToObj(i -> {
            UUID account = accounts.upsert(ISSUER, UUID.randomUUID().toString(), "Rating player").id();
            var character = characters.saveAndFlush(CharacterIdentity.declared(account, "Player" + i, "Fixture"));
            var selection = new SeasonSelection(account, season);
            selection.choose(character.getId(), RatingSubjectType.ACCOUNT, MatchRole.DPS, false);
            confirmed.initializeSelection(selections.saveAndFlush(selection).getId());
            return new SoloQueueCandidate(account, character.getId(), season, "EU", "fixture", "fixture",
                    MatchRole.DPS, false, Instant.now(), 1500, "WARRIOR", 0);
        }).toList();
    }

    private MatchEntity freeze(Queue queue, List<SoloQueueCandidate> a, List<SoloQueueCandidate> b) {
        var now = Instant.now();
        UUID captainA = a.getFirst().accountId();
        UUID captainB = b.getFirst().accountId();
        var proposal = new SoloQueueMatcher.Proposal(a, b, captainA, captainB, new SoloQueueMatcher.Composition(0, 0, 10));
        UUID room = proposals.saveAndFlush(new QueueProposal(proposal, now)).getId();
        var shared = createMatch(captainA, MatchMode.MATCHMADE);
        var roomEntity = new MatchRoomEntity(room, season, captainA, captainB, now, "FOREVER");
        roomEntity.linkMatch(shared.getId());
        rooms.saveAndFlush(roomEntity);
        for (var player : a) assignments.save(new MatchAssignmentEntity(shared, player.accountId(), player.characterId(), "A"));
        for (var player : b) assignments.save(new MatchAssignmentEntity(shared, player.accountId(), player.characterId(), "B"));
        assignments.flush();
        ratings.freeze(shared.getId(), room, season, queue, RatingSubjectType.ACCOUNT, proposal);
        return shared;
    }

    private org.springframework.test.web.servlet.ResultActions standings(Queue queue) throws Exception {
        return mvc.perform(get("/api/v1/local/leaderboard").param("season", season.toString()).param("queue", queue.name()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seasonId").value(season.toString()));
    }

    private org.springframework.test.web.servlet.ResultActions profile(UUID character) throws Exception {
        return mvc.perform(get("/api/v1/local/leaderboard/profile")
                .param("season", season.toString()).param("character", character.toString()));
    }

    private MatchEntity createMatch(UUID actor, MatchMode mode) {
        var now = Instant.now();
        return matches.saveAndFlush(new MatchEntity(actor, now, now.plusSeconds(600), "[]",
                mode, MatchEvidencePolicy.FULL_ROSTER, "FOREVER_FIXTURE", now.plusSeconds(3600)));
    }

    private void confirm(MatchEntity match, String winner) {
        match.result("VERIFIED", null, "WIN", winner, 2, 2);
        matches.flush();
        ratings.synchronize(match);
    }

    private ConfirmedRatingStore.Snapshot rating(Queue queue, List<SoloQueueCandidate> team) {
        return confirmed.confirmed(season, queue, RatingSubjectType.ACCOUNT, team.getFirst().accountId()).orElseThrow();
    }

    private void setMmr(UUID account, int mmr) {
        db.update("UPDATE rbgs.competitive_ratings SET mmr=? WHERE season_id=? AND queue_type='SOLO' AND subject_id=?",
                mmr, season, account);
    }

    private long position(MatchEntity match) {
        return db.queryForObject("SELECT rating_order FROM rbgs.match_competitive_context WHERE match_id=?", Long.class, match.getId());
    }

    private String snapshot(MatchEntity match) {
        return db.queryForObject("SELECT snapshot FROM rbgs.match_competitive_context WHERE match_id=?", String.class, match.getId());
    }

    private int count(String table, String column, UUID match) {
        return db.queryForObject("SELECT count(*) FROM rbgs." + table + " WHERE " + column + "=?", Integer.class, match);
    }
}
