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
    @Autowired MatchHistoryService history;
    @Autowired MatchRepository matchRepository;
    @Autowired MatchAssignmentRepository assignmentRepository;
    @Autowired MatchDisputeService disputes;
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
        assertNotNull(history.detail(auth(0), match).capture());
        assertEquals("RECEIVED", history.detail(auth(0), match).capture().delivery());
        assertNull(history.detail(auth(0), match).capture().stats());
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(12)).andExpect(jsonPath("$.events[?(@.status == 'VERIFIED')]").isNotEmpty());
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

    MatchResultService.ModeratorDecision decision(String outcome, String winner, List<UUID> ids) {
        return new MatchResultService.ModeratorDecision(results.result(auth(0), match).version(), outcome, winner,
                "Reviewed the original scoreboard and assigned teams.", ids);
    }

    @Test void moderator_resolves_supported_outcome_without_quorum_and_preserves_evidence() throws Exception {
        var receipt = submit(0, payload(0, 1, false));
        results.processPending();
        String raw = db.queryForObject("SELECT redacted_payload FROM rbgs.capture_evidence WHERE id=?", String.class, receipt.evidenceId());
        var result = results.decide(auth(0), match, decision("WIN", "A", List.of(receipt.evidenceId())));
        assertEquals("RESOLVED", result.status()); assertEquals("A", result.winningTeam());
        assertNotNull(result.explanation()); assertNotNull(result.resolvedAt()); assertFalse(result.ratingApplied());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND actor_id=? AND decision_reason IS NOT NULL AND expected_version IS NOT NULL", Integer.class, match, players.get(0).id()));
        results.processPending();
        assertEquals(result, results.result(auth(1), match));
        assertEquals(raw, db.queryForObject("SELECT redacted_payload FROM rbgs.capture_evidence WHERE id=?", String.class, receipt.evidenceId()));
        var signedIn = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.get(1)));
        mvc.perform(get("/api/v1/matches/" + match + "/result").with(signedIn))
                .andExpect(status().isOk()).andExpect(jsonPath("$.explanation").value(result.explanation()))
                .andExpect(jsonPath("$.actorId").doesNotExist()).andExpect(jsonPath("$.evidenceIds").doesNotExist());
        mvc.perform(post("/api/v1/moderation/capture-tests/" + match + "/cancel")
                .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.getFirst()))).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test void draw_requires_draw_evidence_and_void_can_resolve_without_reports() throws Exception {
        var value = payload(0, 1, false);
        var outcome = object(object(value.get("capture")).get("outcome"));
        outcome.put("kind", "DRAW"); outcome.put("winningObservedTeamKey", null);
        var receipt = submit(0, value); results.processPending();
        var resolved = results.decide(auth(0), match, decision("DRAW", null, List.of(receipt.evidenceId())));
        assertEquals("DRAW", resolved.outcome()); assertNull(resolved.winningTeam());
        var voided = results.decide(auth(0), match, decision("VOID", null, List.of()));
        assertEquals("VOIDED", voided.status()); assertNull(voided.outcome()); assertNull(voided.winningTeam());
        assertFalse(voided.ratingApplied());
        assertEquals(1, results.list(auth(0), "VOIDED", 50, null).items().stream().filter(m -> match.equals(m.matchId())).count());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND correction_required=true AND previous_status='RESOLVED' AND previous_outcome_kind='DRAW'", Integer.class, match));
    }

    @Test void decisions_reject_missing_reason_stale_versions_and_unsupported_outcomes() throws Exception {
        var receipt = submit(0, payload(0, 1, false));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> results.decide(auth(0), match, decision("WIN", "A", List.of(receipt.evidenceId())))).getStatusCode().value());
        results.processPending();
        for (var invalid : List.of(decision("WIN", "B", List.of(receipt.evidenceId())), decision("DRAW", null, List.of(receipt.evidenceId())),
                decision("WIN", "A", List.of()), decision("VOID", null, List.of(UUID.randomUUID())), decision("WIN", null, List.of(receipt.evidenceId())),
                new MatchResultService.ModeratorDecision(null, "VOID", null, "reason", List.of()),
                new MatchResultService.ModeratorDecision(0L, "VOID", null, "  ", List.of()),
                new MatchResultService.ModeratorDecision(0L, "VOID", null, "x".repeat(2001), List.of())))
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> results.decide(auth(0), match, invalid)).getStatusCode().value());
        var old = decision("VOID", null, List.of());
        results.decide(auth(0), match, old);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> results.decide(auth(0), match, old)).getStatusCode().value());
    }

    @Test void late_evidence_reopens_manual_result_and_new_decision_preserves_history() throws Exception {
        var first = submit(0, payload(0, 1, false)); results.processPending();
        results.decide(auth(0), match, decision("WIN", "A", List.of(first.evidenceId())));
        var opposite = payload(1, 1, false);
        object(object(opposite.get("capture")).get("outcome")).put("winningObservedTeamKey", "1");
        var second = submit(1, opposite); results.processPending();
        var review = results.result(auth(1), match);
        assertEquals("REVIEW", review.status()); assertEquals("LATE_EVIDENCE_AFTER_DECISION", review.reason());
        assertNull(review.outcome()); assertNull(review.explanation());
        var corrected = results.decide(auth(0), match, decision("WIN", "B", List.of(second.evidenceId())));
        assertEquals("B", corrected.winningTeam());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND actor_id IS NOT NULL", Integer.class, match));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND status='RESOLVED' AND winning_team='A'", Integer.class, match));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND correction_required=true", Integer.class, match));
    }

    @Test void concurrent_moderators_cannot_both_finalize_the_same_version() throws Exception {
        var request = decision("VOID", null, List.of());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> work = () -> {
                gate.await();
                try { results.decide(auth(0), match, request); return 200; }
                catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
            };
            var a = pool.submit(work); var b = pool.submit(work); gate.countDown();
            assertEquals(Set.of(200, 409), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND actor_id IS NOT NULL", Integer.class, match));
    }

    @Test void decision_endpoint_enforces_current_moderator_csrf_and_returns_new_version() throws Exception {
        String path = "/api/v1/moderation/matches/" + match + "/decisions";
        String body = json.encode(Map.of("expectedVersion", results.result(auth(0), match).version(), "outcome", "VOID", "reason", "No reliable result.", "evidenceIds", List.of()));
        var login = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.getFirst()));
        mvc.perform(post(path).contentType("application/json").content(body).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType("application/json").content(body).with(login)).andExpect(status().isForbidden());
        mvc.perform(post(path).contentType("application/json").content(body)
                .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.get(1)))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(path).contentType("application/json").content(body).with(login).with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("VOIDED")).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(path).contentType("application/json").content(body).with(login).with(csrf())).andExpect(status().isConflict());
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?", players.get(0).id());
        mvc.perform(post(path).contentType("application/json").content(body).with(login).with(csrf())).andExpect(status().isForbidden());
    }

    @Test void cancelled_matches_cannot_be_resolved() throws Exception {
        controlled.cancel(auth(0), match);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> results.decide(auth(0), match, decision("VOID", null, List.of()))).getStatusCode().value());
    }

    @Test void participant_history_is_private_paginated_filtered_and_does_not_expose_moderator_data() throws Exception {
        quorum();
        var first = history.list(auth(0), "ALL", 1, null);
        assertEquals(match, first.items().getFirst().matchId()); assertEquals("WIN", first.items().getFirst().result());
        assertEquals("LOSS", history.list(auth(10), "LOSS", 20, null).items().getFirst().result());
        assertEquals(0, history.list(auth(10), "WIN", 20, null).items().size());
        var login = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.get(1)));
        var response = mvc.perform(get("/api/v1/me/matches").with(login)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("accountId")); assertFalse(response.contains("assignmentCode")); assertFalse(response.contains("evidenceIds"));
        mvc.perform(get("/api/v1/me/matches")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/matches").param("cursor", "!invalid").with(login)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/matches").param("limit", "51").with(login)).andExpect(status().isBadRequest());
        String outsider = UUID.randomUUID().toString(); subjects.add(outsider); players.add(accounts.upsert(ISSUER, outsider, "OutsiderFixture"));
        var foreign = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(outsider));
        mvc.perform(get("/api/v1/me/matches/"+match).with(foreign)).andExpect(status().isNotFound());
        assertEquals(0, history.list(auth(20), "ALL", 20, null).items().size());
    }

    @Test void own_statistics_are_observations_with_unknowns_not_invented_zeroes_or_other_player_rows() throws Exception {
        var value = payload(0, 1, false); submit(0, value); results.processPending();
        var detail = history.detail(auth(0), match);
        assertNotNull(detail.capture()); assertEquals(0L, detail.capture().stats().damage());
        assertNull(detail.capture().stats().deaths()); assertNull(detail.capture().stats().healing());
        assertEquals("Player0 Family", detail.match().character().name());
        assertEquals(1, detail.roster().stream().filter(MatchHistoryService.Player::you).count());
        var login = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.get(0)));
        String body = mvc.perform(get("/api/v1/me/matches/"+match).with(login)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String secret : List.of("redactedPayload", "normalizedFacts", "accountId", "deviceId", "assignmentCode", "actorId", "evidenceIds")) assertFalse(body.contains(secret), secret);
        assertNull(history.detail(auth(1), match).capture());
        db.update("UPDATE rbgs.matches SET evidence_policy='CAPTURE_ONLY' WHERE id=?",match);
        var wrongCharacter=payload(0,2,false);wrongCharacter.put("observedCharacter",character(1));
        submit(0,wrongCharacter);results.processPending();
        assertNotNull(history.detail(auth(0),match).capture());assertNull(history.detail(auth(0),match).capture().stats());
    }

    @Test void dispute_requests_replies_resolution_and_read_receipts_survive_fresh_queries() throws Exception {
        var receipt=submit(1,payload(1,1,false));results.processPending();
        results.decide(auth(0),match,decision("WIN","A",List.of(receipt.evidenceId())));
        var initial=history.detail(auth(1),match); assertTrue(initial.match().unread());
        disputes.markRead(auth(1),match,initial.match().verification().version(),null);
        assertFalse(history.detail(auth(1),match).match().unread());
        var opened=disputes.open(auth(1),match,new MatchDisputeService.Write(initial.match().verification().version(),"The result needs a review."));
        assertEquals("OPEN",opened.status());assertEquals("REVIEW",results.result(auth(1),match).status());
        assertNull(results.result(auth(1),match).outcome());assertEquals("PLAYER_DISPUTE",results.result(auth(1),match).reason());
        assertNull(history.detail(auth(2),match).dispute());
        var requested=disputes.requestEvidence(auth(0),match,opened.id(),new MatchDisputeService.Write(opened.version(),"Please resync the saved final scoreboard."));
        assertEquals("WAITING_PLAYER",requested.status());assertTrue(history.detail(auth(1),match).match().unread());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->disputes.markRead(auth(1),match,results.result(auth(1),match).version(),opened.version())).getStatusCode().value());
        var reply=disputes.reply(auth(1),match,new MatchDisputeService.Write(requested.version(),"I resynced the saved capture."));
        assertEquals("OPEN",reply.status());assertEquals(3,reply.messages().size());
        results.decide(auth(0),match,decision("WIN","A",List.of(receipt.evidenceId())));
        var resolved=history.detail(auth(1),match);assertEquals("RESOLVED",resolved.dispute().status());assertTrue(resolved.match().unread());
        assertEquals("DECISION",resolved.dispute().messages().getLast().kind());
        disputes.markRead(auth(1),match,resolved.match().verification().version(),resolved.dispute().version());
        assertFalse(history.detail(auth(1),match).match().unread());
        assertEquals(4,disputes.moderation(auth(0),match).getFirst().thread().messages().size());
    }

    @Test void duplicate_stale_closed_and_cancelled_dispute_mutations_are_rejected() throws Exception {
        assertEquals(409,assertThrows(ResponseStatusException.class,()->disputes.open(auth(1),match,new MatchDisputeService.Write(0L,"Too early"))).getStatusCode().value());
        var receipt=submit(1,payload(1,1,false));results.processPending();
        results.decide(auth(0),match,decision("WIN","A",List.of(receipt.evidenceId())));
        long version=results.result(auth(1),match).version();
        assertEquals(400,assertThrows(ResponseStatusException.class,()->disputes.open(auth(1),match,new MatchDisputeService.Write(version,"   "))).getStatusCode().value());
        var thread=disputes.open(auth(1),match,new MatchDisputeService.Write(version,"Review please"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->disputes.open(auth(1),match,new MatchDisputeService.Write(version,"Duplicate"))).getStatusCode().value());
        var request=disputes.requestEvidence(auth(0),match,thread.id(),new MatchDisputeService.Write(thread.version(),"Please provide more context"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->disputes.reply(auth(1),match,new MatchDisputeService.Write(thread.version(),"Stale"))).getStatusCode().value());
        results.decide(auth(0),match,decision("VOID",null,List.of()));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->disputes.reply(auth(1),match,new MatchDisputeService.Write(request.version(),"Closed"))).getStatusCode().value());
        assertEquals("OPEN",disputes.open(auth(1),match,new MatchDisputeService.Write(results.result(auth(1),match).version(),"I dispute the corrected decision")).status());
    }

    @Test void dispute_routes_enforce_participation_moderator_role_csrf_and_current_account_status() throws Exception {
        results.decide(auth(0),match,decision("VOID",null,List.of()));
        String path="/api/v1/me/matches/"+match+"/disputes";
        String body=json.encode(Map.of("expectedVersion",results.result(auth(1),match).version(),"message","Review this void decision"));
        var login=oidcLogin().idToken(t->t.issuer(ISSUER).subject(subjects.get(1)));
        mvc.perform(post(path).with(login).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(login).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].author").value("PLAYER")).andExpect(jsonPath("$.messages[0].authorId").doesNotExist());
        mvc.perform(get("/api/v1/moderation/matches/"+match+"/disputes").with(login)).andExpect(status().isForbidden());
        assertNull(history.detail(auth(2),match).dispute());
        var thread=history.detail(auth(1),match).dispute();
        mvc.perform(post("/api/v1/me/matches/"+match+"/read").with(login).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->disputes.requestEvidence(auth(0),UUID.randomUUID(),thread.id(),new MatchDisputeService.Write(thread.version(),"Foreign match"))).getStatusCode().value());
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?",players.get(1).id());
        mvc.perform(get("/api/v1/me/matches").with(login)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(login).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }

    @Test void concurrent_dispute_opens_create_one_thread_and_preserve_the_original_decision() throws Exception {
        results.decide(auth(0),match,decision("VOID",null,List.of()));
        var request=new MatchDisputeService.Write(results.result(auth(1),match).version(),"Review the outcome");
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);
            Callable<Integer> work=()->{gate.await();try{disputes.open(auth(1),match,request);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();
            assertEquals(Set.of(200,409),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM rbgs.match_disputes WHERE match_id=?",Integer.class,match));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND status='VOIDED'",Integer.class,match));
        results.processPending();assertEquals("PLAYER_DISPUTE",results.result(auth(1),match).reason());
    }

    @Test void history_cursor_has_stable_order_under_ties_and_never_repeats_a_match() {
        var original=matchRepository.findById(match).orElseThrow();
        var created=new ArrayList<MatchEntity>();
        UUID ownCharacter=db.queryForObject("SELECT character_id FROM rbgs.match_assignments WHERE match_id=? AND account_id=?",UUID.class,match,players.get(1).id());
        try {
            for(int i=0;i<3;i++){
                var test=matchRepository.saveAndFlush(new MatchEntity(players.get(0).id(),Instant.parse("2099-01-01T00:00:00Z"),Instant.parse("2099-01-01T01:00:00Z"),original.getExpectedRoster(),MatchMode.CONTROLLED_TEST,MatchEvidencePolicy.FULL_ROSTER,original.getProfile(),Instant.parse("2099-01-01T05:00:00Z")));
                created.add(test);
                assignmentRepository.saveAndFlush(new MatchAssignmentEntity(test,players.get(1).id(),ownCharacter,"A"));
            }
            var first=history.list(auth(1),"ALL",2,null);
            var second=history.list(auth(1),"ALL",2,first.nextCursor());
            var ids=new ArrayList<UUID>();first.items().forEach(m->ids.add(m.matchId()));second.items().forEach(m->ids.add(m.matchId()));
            assertEquals(4,new HashSet<>(ids).size());assertNull(second.nextCursor());
            assertEquals(created.stream().map(MatchEntity::getId).sorted(Comparator.comparing(UUID::toString).reversed()).toList(),ids.subList(0,3));
        } finally { for(var test:created){db.update("DELETE FROM rbgs.match_assignments WHERE match_id=?",test.getId());db.update("DELETE FROM rbgs.matches WHERE id=?",test.getId());} }
    }

    @Test void cancelled_history_cannot_be_disputed() {
        controlled.cancel(auth(0),match);
        assertFalse(history.detail(auth(1),match).canDispute());
        assertEquals("CANCELLED",history.detail(auth(1),match).match().result());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->disputes.open(auth(1),match,new MatchDisputeService.Write(results.result(auth(1),match).version(),"Cannot dispute cancellation"))).getStatusCode().value());
    }

    @Test void dispute_rate_limit_preserves_all_accepted_messages() {
        results.decide(auth(0),match,decision("VOID",null,List.of()));
        var thread=disputes.open(auth(1),match,new MatchDisputeService.Write(results.result(auth(1),match).version(),"Review please"));
        for(int i=0;i<9;i++)thread=disputes.reply(auth(1),match,new MatchDisputeService.Write(thread.version(),"Additional context "+i));
        var version=thread.version();
        assertEquals(429,assertThrows(ResponseStatusException.class,()->disputes.reply(auth(1),match,new MatchDisputeService.Write(version,"Too many"))).getStatusCode().value());
        assertEquals(10,history.detail(auth(1),match).dispute().messages().size());
    }

    @Test void cancelling_an_unresolved_test_closes_its_dispute_with_a_player_visible_notification() {
        db.update("UPDATE rbgs.matches SET verification_deadline=now()-interval '1 second' WHERE id=?",match);
        results.processPending();
        var opened=disputes.open(auth(1),match,new MatchDisputeService.Write(results.result(auth(1),match).version(),"Please review this test"));
        controlled.cancel(auth(0),match);
        var detail=history.detail(auth(1),match);
        assertEquals("CANCELLED",detail.match().result());assertFalse(detail.canDispute());assertTrue(detail.match().unread());
        assertEquals("RESOLVED",detail.dispute().status());assertEquals("CANCELLED",detail.dispute().messages().getLast().kind());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->disputes.reply(auth(1),match,new MatchDisputeService.Write(opened.version(),"This test is cancelled"))).getStatusCode().value());
    }
}
