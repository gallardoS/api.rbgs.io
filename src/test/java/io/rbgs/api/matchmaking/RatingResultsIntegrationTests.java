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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "rbgs.rating.enabled=true")
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
    @Autowired MatchResultService results;
    @Autowired ConfirmedRatingStore confirmed;
    @Autowired RatingResults ratings;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entityManager;
    private UUID season;

    @BeforeEach void setUp() {
        season = seasons.saveAndFlush(new SeasonEntity("Rating fixture", SeasonKind.BETA, RatingSubjectType.ACCOUNT)).getId();
    }

    @Test void lateResultAndCorrectionReplayInCreationOrderWithoutDoubleApplying() {
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
    }

    @Test void moderatorVoidReversesMovementsAndKeepsTheAuditTrail() {
        var a = team();
        var b = team();
        var match = freeze(Queue.PREMADE, a, b);
        confirm(match, "A");
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
        ratings.freeze(shared.getId(), room, season, queue, RatingSubjectType.ACCOUNT, proposal);
        return shared;
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
