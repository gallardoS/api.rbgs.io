package io.rbgs.api.matches;

import static io.rbgs.api.evidence.CaptureJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.evidence.*;
import io.rbgs.api.identity.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"rbgs.capture.controlled-enabled=false", "rbgs.matches.processing-delay-ms=3600000"})
@AutoConfigureMockMvc
@Transactional
class SharedMatchDomainTests {
    @Autowired MatchRepository matches;
    @Autowired MatchAssignmentRepository assignments;
    @Autowired MatchResultService results;
    @Autowired ControlledMatchService controlled;
    @Autowired CaptureService captures;
    @Autowired CaptureJson json;
    @Autowired AccountService accounts;
    @Autowired DeviceService devices;
    @Autowired MockMvc mvc;
    final List<Account> players = new ArrayList<>();
    final List<String> subjects = new ArrayList<>();
    static final String ISSUER = "https://shared-match.example";

    OAuth2AuthenticationToken auth(int index) {
        Instant now = Instant.now();
        var token = new OidcIdToken("fixture", now, now.plusSeconds(300), Map.of("iss", ISSUER, "sub", subjects.get(index)));
        var roles = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(roles, token), roles, "battle-net");
    }

    Map<String, Object> character(int index) { return Map.of("name", "Shared" + index + " Family", "realm", "FixtureRealm"); }

    MatchEntity seed(MatchMode mode, String profile, Instant deadline) {
        if (players.isEmpty()) {
            for (int i = 0; i < 12; i++) {
                String subject = UUID.randomUUID().toString(); subjects.add(subject);
                players.add(accounts.upsert(ISSUER, subject, "SharedMatchFixture"));
            }
        }
        var roster = new ArrayList<Map<String, Object>>();
        var characters = new ArrayList<UUID>();
        for (int i = 0; i < 12; i++) {
            var id = UUID.randomUUID(); characters.add(id);
            roster.add(Map.of("characterId", id.toString(), "character", character(i), "assignedTeamKey", i < 6 ? "A" : "B"));
        }
        var match = matches.saveAndFlush(new MatchEntity(mode == MatchMode.CONTROLLED_TEST ? players.getFirst().id() : null,
                Instant.now().minusSeconds(90), Instant.now().plusSeconds(600), json.encode(roster), mode,
                MatchEvidencePolicy.FULL_ROSTER, profile, deadline));
        for (int i = 0; i < 12; i++) assignments.save(new MatchAssignmentEntity(match, players.get(i).id(), characters.get(i), i < 6 ? "A" : "B"));
        assignments.flush();
        return match;
    }

    @Test void matchmade_result_uses_the_same_capture_and_quorum_engine_with_tests_disabled() throws Exception {
        var match = seed(MatchMode.MATCHMADE, CaptureProfile.DARKSPEAR_DIAGNOSTIC.name(), Instant.now().plusSeconds(3600));
        String firstBearer = null;
        UUID firstCapture = null;
        for (int i = 0; i < 12; i++) {
            var pairing = devices.start(DeviceService.CAPTURE_SCOPE);
            devices.approve(auth(i), pairing.code(), true);
            var token = devices.exchange(pairing.pollingSecret());
            var assignment = assignments.listOwn(players.get(i).id(), false, org.springframework.data.domain.PageRequest.of(0, 50)).getFirst();
            var issued = captures.issue(players.get(i).id(), assignment.getCaptureId());
            Map<String, Object> report;
            try (var stream = getClass().getResourceAsStream("/capture/evidence-wire.sanitized.json")) { report = json.parse(stream.readAllBytes()); }
            report.put("matchId", match.getId().toString());
            report.put("assignmentId", assignment.getId().toString());
            report.put("captureId", assignment.getCaptureId().toString());
            report.put("assignmentCode", issued.get("assignmentCode"));
            report.put("observedCharacter", character(i));
            var capture = object(report.get("capture"));
            capture.put("startedAt", Instant.now().minusSeconds(30).toString());
            capture.put("endedAt", Instant.now().minusSeconds(1).toString());
            capture.put("outcome", Map.of("kind", "WIN", "winningObservedTeamKey", "0"));
            var roster = new ArrayList<Map<String, Object>>();
            for (int n = 0; n < 12; n++) roster.add(Map.of("character", character(n), "observedTeamKey", n < 6 ? "0" : "1"));
            capture.put("roster", roster);
            var receipt = captures.ingest(devices.authorize("Bearer " + token.token(), "evidence:write"), report);
            assertTrue(receipt.durablyStored());
            assertEquals(receipt.evidenceId(), captures.ingest(devices.authorize("Bearer " + token.token(), "evidence:write"), report).evidenceId());
            assertEquals("MATCHMADE", object(issued.get("assignment")).get("mode"));
            if (i == 0) { firstBearer = token.token(); firstCapture = assignment.getCaptureId(); }
        }
        results.processPending();
        matches.flush();
        var result = results.result(auth(0), match.getId());
        assertEquals("MATCHMADE", result.mode());
        assertEquals("VERIFIED", result.status());
        assertEquals("A", result.winningTeam());
        assertEquals(6, result.reportsA()); assertEquals(6, result.reportsB());
        assertFalse(result.ratingApplied());
        assertTrue(assignments.existsByMatchIdAndAccountId(match.getId(), players.getFirst().id()));
        mvc.perform(get("/api/v1/matches/" + match.getId() + "/result").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.getFirst()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("MATCHMADE")).andExpect(jsonPath("$.status").value("VERIFIED"));
        mvc.perform(get("/api/v1/companion/assignments/" + firstCapture + "/result").header("Authorization", "Bearer " + firstBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("MATCHMADE"));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> controlled.create(auth(0), Map.of())).getStatusCode().value());
    }

    @Test void disabled_controlled_matches_are_hidden_and_skipped_while_ordinary_matches_expire() {
        var testMatch = seed(MatchMode.CONTROLLED_TEST, CaptureProfile.DARKSPEAR_DIAGNOSTIC.name(), Instant.now().minusSeconds(1));
        var ordinary = seed(MatchMode.MATCHMADE, CaptureProfile.DARKSPEAR_DIAGNOSTIC.name(), Instant.now().minusSeconds(1));
        results.processPending();
        assertEquals("PENDING", testMatch.getVerificationStatus());
        assertEquals("REVIEW", ordinary.getVerificationStatus());
        assertEquals("INSUFFICIENT_REPORTS", ordinary.getVerificationReason());
        assertEquals(1, captures.list(players.getFirst().id()).size());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> results.result(auth(0), testMatch.getId())).getStatusCode().value());
    }

    @Test void unknown_profile_can_be_persisted_but_cannot_produce_a_verified_result() {
        var match = seed(MatchMode.MATCHMADE, "UNVALIDATED_WSG", Instant.now().minusSeconds(1));
        results.processPending();
        assertEquals("REVIEW", match.getVerificationStatus());
        assertEquals("UNSUPPORTED_CAPTURE_PROFILE", match.getVerificationReason());
        assertNull(match.getWinningTeam());
    }
}
