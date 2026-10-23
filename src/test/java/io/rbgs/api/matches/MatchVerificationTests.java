package io.rbgs.api.matches;

import io.rbgs.api.evidence.*;


import static io.rbgs.api.evidence.CaptureJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.identity.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"rbgs.capture.controlled-enabled=true", "rbgs.capture.processing-delay-ms=3600000"})
@AutoConfigureMockMvc
class MatchVerificationTests {
    @Autowired CaptureService captures;
    @Autowired ControlledMatchService controlled;
    @Autowired MatchResultService results;
    @Autowired CaptureJson json;
    @Autowired AccountService accounts;
    @Autowired DeviceService devices;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    final List<Account> players = new ArrayList<>();
    final List<String> subjects = new ArrayList<>();
    final Map<Integer, Map<String, Object>> issued = new HashMap<>();
    final Map<Integer, DeviceService.Token> tokens = new HashMap<>();
    List<Map<String, Object>> descriptors;
    UUID match;
    Instant start, end;
    static final String ISSUER = "https://verification.example";

    @BeforeEach void setup() {
        for (int i = 0; i < 20; i++) {
            String subject = UUID.randomUUID().toString();
            subjects.add(subject);
            players.add(accounts.upsert(ISSUER, subject, "VerificationFixture"));
        }
        db.update("UPDATE rbgs.accounts SET account_role='MODERATOR' WHERE id=?", players.getFirst().id());
        var roster = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 20; i++) roster.add(Map.of("accountId", players.get(i).id().toString(), "character", character(i), "teamKey", i < 10 ? "A" : "B"));
        var created = controlled.create(auth(0), Map.of("armMinutes", 15, "roster", roster));
        match = (UUID)created.get("matchId");
        descriptors = ((List<?>)created.get("assignments")).stream().map(CaptureJson::object).toList();
        start = Instant.now().minusSeconds(60);
        end = Instant.now().minusSeconds(1);
    }

    @AfterEach void cleanup() {
        if (match != null) {
            db.update("DELETE FROM rbgs.capture_processing_jobs WHERE evidence_id IN (SELECT e.id FROM rbgs.capture_evidence e JOIN rbgs.match_assignments a ON a.id=e.assignment_id WHERE a.match_id=?)", match);
            db.update("DELETE FROM rbgs.capture_evidence WHERE assignment_id IN (SELECT id FROM rbgs.match_assignments WHERE match_id=?)", match);
            db.update("DELETE FROM rbgs.match_assignments WHERE match_id=?", match);
            db.update("DELETE FROM rbgs.matches WHERE id=?", match);
        }
        for (var player : players) {
            db.update("DELETE FROM rbgs.device_pairings WHERE account_id=?", player.id());
            db.update("DELETE FROM rbgs.devices WHERE account_id=?", player.id());
            db.update("DELETE FROM rbgs.accounts WHERE id=?", player.id());
        }
    }

    OAuth2AuthenticationToken auth(int i) {
        Instant now = Instant.now();
        var id = new OidcIdToken("fixture", now, now.plusSeconds(300), Map.of("iss", ISSUER, "sub", subjects.get(i)));
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, id), authorities, "battle-net");
    }

    Map<String, Object> character(int i) { return Map.of("name", "Player" + i + " Family", "realm", "FixtureRealm"); }

    DeviceService.Token token(int i) {
        return tokens.computeIfAbsent(i, index -> {
            var pairing = devices.start(DeviceService.CAPTURE_SCOPE);
            devices.approve(auth(index), pairing.code(), true);
            return devices.exchange(pairing.pollingSecret());
        });
    }

    Map<String, Object> payload(int i, int revision, boolean reverse) throws Exception {
        var command = issued.computeIfAbsent(i, index -> captures.issue(players.get(index).id(), (UUID)descriptors.get(index).get("captureId")));
        Map<String, Object> value;
        try (var source = getClass().getResourceAsStream("/capture/evidence-wire.sanitized.json")) { value = json.parse(source.readAllBytes()); }
        value.put("matchId", match.toString());
        value.put("assignmentId", descriptors.get(i).get("assignmentId").toString());
        value.put("captureId", descriptors.get(i).get("captureId").toString());
        value.put("assignmentCode", command.get("assignmentCode"));
        value.put("captureRevision", revision);
        value.put("observedCharacter", character(i));
        var c = object(value.get("capture"));
        c.put("startedAt", start.toString());
        c.put("endedAt", end.toString());
        c.put("outcome", new LinkedHashMap<>(Map.of("kind", "WIN", "winningObservedTeamKey", reverse ? "1" : "0")));
        var roster = new ArrayList<Map<String, Object>>();
        for (int n = 0; n < 20; n++) {
            var observedCharacter = new LinkedHashMap<String, Object>(character(n));
            observedCharacter.put("realm", null);
            roster.add(new LinkedHashMap<>(Map.of("character", observedCharacter, "observedTeamKey", (n < 10) != reverse ? "0" : "1", "damage", n * (i + 1))));
        }
        c.put("roster", roster);
        return value;
    }

    CaptureService.Receipt submit(int player, Map<String, Object> payload) {
        return captures.ingest(devices.authorize("Bearer " + token(player).token(), "evidence:write"), payload);
    }

    void quorum() throws Exception {
        for (int i : new int[] {0, 1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 15}) submit(i, payload(i, 1, i % 2 == 0));
        results.processPending();
    }

    @Test void six_per_team_verify_mapped_winner_despite_optional_statistics_and_perspectives() throws Exception {
        quorum();
        var result = results.result(auth(1), match);
        assertEquals("VERIFIED", result.status());
        assertEquals("A", result.winningTeam());
        assertEquals("WIN", result.outcome());
        assertEquals(6, result.reportsA());
        assertEquals(6, result.reportsB());
        assertFalse(result.ratingApplied());
        assertEquals(12, db.queryForObject("SELECT count(*) FROM rbgs.capture_evidence e JOIN rbgs.match_assignments a ON a.id=e.assignment_id WHERE a.match_id=? AND e.validation_status='VALID'", Integer.class, match));
        String raw = db.queryForObject("SELECT redacted_payload FROM rbgs.capture_evidence WHERE assignment_id=?", String.class, descriptors.get(0).get("assignmentId"));
        results.processPending();
        assertEquals(result, results.ownResult(players.get(0).id(), (UUID)descriptors.get(0).get("captureId")));
        assertEquals(raw, db.queryForObject("SELECT redacted_payload FROM rbgs.capture_evidence WHERE assignment_id=?", String.class, descriptors.get(0).get("assignmentId")));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM rbgs.capture_processing_jobs j JOIN rbgs.capture_evidence e ON e.id=j.evidence_id JOIN rbgs.match_assignments a ON a.id=e.assignment_id WHERE a.match_id=?", Integer.class, match));
    }

    @Test void ten_on_one_side_and_many_revisions_do_not_satisfy_quorum() throws Exception {
        for (int i = 0; i < 10; i++) submit(i, payload(i, 1, false));
        for (int revision = 2; revision < 8; revision++) submit(0, payload(0, revision, false));
        results.processPending();
        var result = results.result(auth(0), match);
        assertEquals("PENDING", result.status());
        assertEquals(10, result.reportsA());
        assertEquals(0, result.reportsB());
        assertNull(result.winningTeam());
    }

    @Test void late_contradiction_from_same_reporter_reopens_review_without_overwriting_verified_event() throws Exception {
        quorum();
        var contradiction = payload(0, 2, false);
        object(object(contradiction.get("capture")).get("outcome")).put("winningObservedTeamKey", "1");
        submit(0, contradiction);
        results.processPending();
        var result = results.result(auth(0), match);
        assertEquals("REVIEW", result.status());
        assertEquals("CONTRADICTORY_OUTCOME", result.reason());
        assertNull(result.outcome());
        assertEquals(6, result.reportsA());
        assertTrue(result.version() > 0);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND status='VERIFIED' AND winning_team='A'", Integer.class, match));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND previous_status='VERIFIED' AND status='REVIEW'", Integer.class, match));
        submit(0, payload(0, 3, false));
        results.processPending();
        assertEquals("REVIEW", results.result(auth(0), match).status());
    }

    @Test void insufficient_reports_expire_without_another_upload_and_cannot_award_default_win() throws Exception {
        submit(0, payload(0, 1, false));
        results.processPending();
        db.update("UPDATE rbgs.matches SET verification_deadline=now()-interval '1 second' WHERE id=?", match);
        results.processPending();
        var result = results.result(auth(0), match);
        assertEquals("REVIEW", result.status());
        assertEquals("INSUFFICIENT_REPORTS", result.reason());
        assertNull(result.winningTeam());
        for (int i : new int[] {0, 1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 15}) submit(i, payload(i, 2, false));
        results.processPending();
        assertEquals("REVIEW", results.result(auth(0), match).status());
    }

    @Test void roster_own_character_team_and_time_conflicts_are_retained_with_validation_reasons() throws Exception {
        var wrongOwn = payload(0, 1, false);
        wrongOwn.put("observedCharacter", character(1));
        var first = submit(0, wrongOwn);
        var duplicate = payload(1, 1, false);
        var rows = (List<?>)object(duplicate.get("capture")).get("roster");
        object(rows.get(1)).put("character", character(0));
        var second = submit(1, duplicate);
        var wrongTeam = payload(2, 1, false);
        object(((List<?>)object(wrongTeam.get("capture")).get("roster")).getFirst()).put("observedTeamKey", "1");
        var third = submit(2, wrongTeam);
        var wrongTime = payload(3, 1, false);
        object(wrongTime.get("capture")).put("endedAt", start.minusSeconds(1).toString());
        var fourth = submit(3, wrongTime);
        results.processPending();
        for (var entry : Map.of(first.evidenceId(), "OWN_CHARACTER_MISMATCH", second.evidenceId(), "DUPLICATE_ROSTER_CHARACTER",
                third.evidenceId(), "TEAM_MAPPING_CONFLICT", fourth.evidenceId(), "IMPLAUSIBLE_TIMING").entrySet()) {
            assertEquals(entry.getValue(), db.queryForObject("SELECT review_reason FROM rbgs.capture_evidence WHERE id=?", String.class, entry.getKey()));
            assertEquals("INVALID", db.queryForObject("SELECT validation_status FROM rbgs.capture_evidence WHERE id=?", String.class, entry.getKey()));
        }
        assertEquals("REVIEW", results.result(auth(0), match).status());
        assertEquals(0, results.result(auth(0), match).reportsA());
    }

    @Test void incomplete_revision_does_not_vote_and_final_revision_can_be_used() throws Exception {
        var incomplete = payload(0, 1, false);
        var capture = object(incomplete.get("capture"));
        capture.put("state", "INCOMPLETE"); capture.put("stage", "RECORDING"); capture.put("incompleteReason", "CAPTURE_PENDING");
        var receipt = submit(0, incomplete);
        results.processPending();
        assertEquals("INCOMPLETE", db.queryForObject("SELECT validation_status FROM rbgs.capture_evidence WHERE id=?", String.class, receipt.evidenceId()));
        assertEquals("PENDING", results.result(auth(0), match).status());
        assertTrue(results.result(auth(0), match).deadline().isAfter(Instant.now().plus(Duration.ofHours(3))));
        submit(0, payload(0, 2, false));
        results.processPending();
        assertEquals(1, results.result(auth(0), match).reportsA());
    }

    @Test void concordant_draw_has_no_winning_team() throws Exception {
        for (int i : new int[] {0, 1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 15}) {
            var payload = payload(i, 1, false);
            var outcome = object(object(payload.get("capture")).get("outcome"));
            outcome.put("kind", "DRAW"); outcome.put("winningObservedTeamKey", null);
            submit(i, payload);
        }
        results.processPending();
        assertEquals("VERIFIED", results.result(auth(0), match).status());
        assertEquals("DRAW", results.result(auth(0), match).outcome());
        assertNull(results.result(auth(0), match).winningTeam());
    }

    @Test void otherwise_valid_reports_for_different_game_times_open_review() throws Exception {
        submit(0, payload(0, 1, false));
        var otherGame = payload(10, 1, false);
        object(otherGame.get("capture")).put("startedAt", start.plusSeconds(130).toString());
        object(otherGame.get("capture")).put("endedAt", end.plusSeconds(110).toString());
        submit(10, otherGame);
        results.processPending();
        assertEquals("CONTRADICTORY_TIMING", results.result(auth(0), match).reason());
        assertEquals(1, results.result(auth(0), match).reportsA());
        assertEquals(1, results.result(auth(0), match).reportsB());
    }

    @Test void delayed_upload_is_usable_when_capture_started_during_the_assignment_window() throws Exception {
        start = Instant.now().minus(Duration.ofMinutes(15)); end = Instant.now().minus(Duration.ofMinutes(5));
        var payload = payload(0, 1, false);
        db.update("UPDATE rbgs.matches SET created_at=now()-interval '30 minutes',capture_start_deadline=now()-interval '10 minutes' WHERE id=?", match);
        db.update("UPDATE rbgs.match_assignments SET issued_at=now()-interval '20 minutes' WHERE match_id=? AND code_hash IS NOT NULL", match);
        var receipt = submit(0, payload);
        results.processPending();
        assertEquals("VALID", db.queryForObject("SELECT validation_status FROM rbgs.capture_evidence WHERE id=?", String.class, receipt.evidenceId()));
        assertEquals("PENDING", results.result(auth(0), match).status());
        assertEquals(1, results.result(auth(0), match).reportsA());
    }

    @Test void overdue_quorum_cannot_verify_even_when_worker_was_offline_at_deadline() throws Exception {
        start = Instant.now().minus(Duration.ofMinutes(20)); end = Instant.now().minus(Duration.ofMinutes(17));
        CaptureService.Receipt first = null;
        for (int i : new int[] {0, 1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 15}) {
            var receipt = submit(i, payload(i, 1, false));
            if (first == null) first = receipt;
        }
        db.update("UPDATE rbgs.matches SET created_at=now()-interval '30 minutes' WHERE id=?", match);
        db.update("UPDATE rbgs.match_assignments SET issued_at=now()-interval '25 minutes' WHERE match_id=? AND code_hash IS NOT NULL", match);
        db.update("UPDATE rbgs.capture_evidence SET received_at=now()-interval '16 minutes' WHERE id=?", first.evidenceId());
        results.processPending();
        assertEquals("REVIEW", results.result(auth(0), match).status());
        assertEquals("INSUFFICIENT_REPORTS", results.result(auth(0), match).reason());
        assertNull(results.result(auth(0), match).outcome());
    }

    @Test void integrity_failure_is_not_counted_as_a_report() throws Exception {
        var receipt = submit(0, payload(0, 1, false));
        db.update("UPDATE rbgs.capture_evidence SET stored_sha256=? WHERE id=?", "0".repeat(64), receipt.evidenceId());
        results.processPending();
        assertEquals("STORED_DATA_INTEGRITY", results.result(auth(0), match).reason());
        assertEquals(0, results.result(auth(0), match).reportsA());
    }

    @Test void test_with_no_reports_expires_and_can_be_cancelled() throws Exception {
        db.update("UPDATE rbgs.matches SET verification_deadline=now()-interval '1 second' WHERE id=?", match);
        results.processPending();
        assertEquals("INSUFFICIENT_REPORTS", results.result(auth(0), match).reason());
        controlled.cancel(auth(0), match);
        assertEquals("ASSIGNMENT_CANCELLED", results.result(auth(0), match).reason());
    }

    @Test void cancellation_of_verified_test_preserves_its_prior_result_in_audit_history() throws Exception {
        quorum();
        controlled.cancel(auth(0), match);
        assertEquals("REVIEW", results.result(auth(0), match).status());
        assertEquals("ASSIGNMENT_CANCELLED", results.result(auth(0), match).reason());
        assertNull(results.result(auth(0), match).outcome());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND status='VERIFIED' AND winning_team='A'", Integer.class, match));
    }

    @Test void participant_device_and_moderation_views_enforce_current_accounts_and_privacy() throws Exception {
        quorum();
        mvc.perform(get("/api/v1/capture-tests/" + match + "/result")).andExpect(status().isUnauthorized());
        var signedIn = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.get(1)));
        mvc.perform(get("/api/v1/capture-tests/" + match + "/result").with(signedIn))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.winningTeam").value("A")).andExpect(jsonPath("$.redactedPayload").doesNotExist())
                .andExpect(jsonPath("$.reports").doesNotExist());
        mvc.perform(get("/api/v1/moderation/capture-tests/" + match + "/verification").with(signedIn)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/moderation/capture-tests/" + match + "/verification").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.getFirst()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(12)).andExpect(jsonPath("$.events[0].status").value("VERIFIED"));
        var foreign = UUID.randomUUID();
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> results.ownResult(foreign, (UUID)descriptors.get(1).get("captureId"))).getStatusCode().value());
        String outsider = UUID.randomUUID().toString();
        subjects.add(outsider); players.add(accounts.upsert(ISSUER, outsider, "OutsiderFixture"));
        mvc.perform(get("/api/v1/capture-tests/" + match + "/result").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(outsider))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companion/assignments/" + descriptors.get(1).get("captureId") + "/result")
                .header("Authorization", "Bearer " + token(0).token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companion/assignments/" + descriptors.get(1).get("captureId") + "/result")
                .header("Authorization", "Bearer " + token(1).token())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?", players.get(1).id());
        mvc.perform(get("/api/v1/capture-tests/" + match + "/result").with(signedIn)).andExpect(status().isForbidden());
    }

    @Test void concurrent_workers_consume_durable_jobs_once() throws Exception {
        for (int i : new int[] {0, 1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 15}) submit(i, payload(i, 1, false));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Void> work = () -> { gate.await(); results.processPending(); return null; };
            var a = pool.submit(work); var b = pool.submit(work); gate.countDown();
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        }
        assertEquals("VERIFIED", results.result(auth(0), match).status());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND status='VERIFIED'", Integer.class, match));
    }

    @Test void shared_routes_and_legacy_aliases_return_the_same_result_and_moderator_details() throws Exception {
        quorum();
        var participant = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.get(1)));
        String canonical = mvc.perform(get("/api/v1/matches/" + match + "/result").with(participant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String legacy = mvc.perform(get("/api/v1/capture-tests/" + match + "/result").with(participant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(canonical, legacy);
        mvc.perform(get("/api/v1/moderation/matches/" + match + "/verification").with(participant)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/moderation/matches/" + match + "/verification")
                .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.getFirst()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(12));
    }

    @Test void controlled_cancel_route_cannot_cancel_a_matchmade_match() throws Exception {
        db.update("UPDATE rbgs.matches SET mode='MATCHMADE' WHERE id=?", match);
        mvc.perform(post("/api/v1/moderation/capture-tests/" + match + "/cancel")
                .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.getFirst()))).with(csrf()))
                .andExpect(status().isNotFound());
        assertNull(db.queryForObject("SELECT cancelled_at FROM rbgs.matches WHERE id=?", Instant.class, match));
        assertEquals("MATCHMADE", results.result(auth(0), match).mode());
    }
}
