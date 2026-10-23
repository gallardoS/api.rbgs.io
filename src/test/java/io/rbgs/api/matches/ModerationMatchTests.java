package io.rbgs.api.matches;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.rbgs.api.evidence.CaptureJson;
import io.rbgs.api.identity.*;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"rbgs.capture.controlled-enabled=true", "rbgs.matches.processing-delay-ms=3600000"})
@AutoConfigureMockMvc
@Transactional
class ModerationMatchTests {
    @Autowired MatchRepository matches;
    @Autowired MatchResultService results;
    @Autowired CaptureJson json;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    String subject;
    Account moderator;
    static final String ISSUER = "https://moderation-list.example";
    static final Instant CREATED = Instant.parse("2090-01-01T12:00:00Z");

    @BeforeEach void setup() {
        subject = UUID.randomUUID().toString();
        moderator = accounts.upsert(ISSUER, subject, "ModeratorFixture");
        db.update("UPDATE rbgs.accounts SET account_role='MODERATOR' WHERE id=?", moderator.id());
        em.clear();
    }

    OAuth2AuthenticationToken auth() {
        Instant now = Instant.now();
        var token = new OidcIdToken("fixture", now, now.plusSeconds(300), Map.of("iss", ISSUER, "sub", subject));
        var roles = List.of(new SimpleGrantedAuthority("ROLE_MODERATOR"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(roles, token), roles, "battle-net");
    }

    MatchEntity match(Instant at, String status, boolean cancelled, MatchMode mode) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("name", "Declared Player"); identity.put("realm", null);
        var row = new LinkedHashMap<String, Object>();
        row.put("characterId", UUID.randomUUID().toString()); row.put("character", identity); row.put("assignedTeamKey", null);
        var match = new MatchEntity(mode == MatchMode.CONTROLLED_TEST ? moderator.id() : null, at, at.plusSeconds(600),
                json.encode(List.of(row)), mode, MatchEvidencePolicy.CAPTURE_ONLY, "DARKSPEAR_DIAGNOSTIC", at.plusSeconds(20000));
        match.result(status, "REVIEW".equals(status) ? "CAPTURE_ONLY_NO_EXPECTED_TEAMS" : null,
                "VERIFIED".equals(status) ? "WIN" : null, "VERIFIED".equals(status) ? "A" : null, 3, 2);
        if (cancelled) match.cancel(at.plusSeconds(100));
        return matches.saveAndFlush(match);
    }

    @Test void list_is_persisted_bounded_and_has_stable_tie_breaking_without_exposing_reports() throws Exception {
        var rows = new ArrayList<MatchEntity>();
        for (int i = 0; i < 5; i++) rows.add(match(CREATED, "PENDING", false, MatchMode.CONTROLLED_TEST));
        var expected = rows.stream().map(MatchEntity::getId).sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        var first = results.list(auth(), "ALL", 2, null);
        var second = results.list(auth(), "ALL", 2, first.nextCursor());
        var third = results.list(auth(), "ALL", 2, second.nextCursor());
        var ids = new ArrayList<UUID>();
        first.items().forEach(m -> ids.add(m.matchId())); second.items().forEach(m -> ids.add(m.matchId()));
        ids.add(third.items().getFirst().matchId());
        assertEquals(expected, ids);
        assertEquals(first.items().getFirst().matchId(), results.list(auth(), "ALL", 2, null).items().getFirst().matchId());
        var response = mvc.perform(get("/api/v1/moderation/matches").param("limit", "2")
                .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.nextCursor").isString())
                .andExpect(jsonPath("$.items[0].roster[0].name").value("Declared Player"))
                .andExpect(jsonPath("$.items[0].result.reportsA").value(3))
                .andExpect(jsonPath("$.items[0].canCancel").value(true)).andReturn();
        String body = response.getResponse().getContentAsString();
        assertFalse(body.contains("redactedPayload")); assertFalse(body.contains("accountId")); assertFalse(body.contains("assignmentCode"));
    }

    @Test void review_and_cancelled_filters_do_not_mix_cancelled_matches_with_unresolved_matches() {
        var review = match(CREATED.plusSeconds(1), "REVIEW", false, MatchMode.CONTROLLED_TEST);
        var cancelled = match(CREATED.plusSeconds(2), "REVIEW", true, MatchMode.CONTROLLED_TEST);
        var future = match(CREATED.plusSeconds(3), "PENDING", false, MatchMode.MATCHMADE);
        assertEquals(review.getId(), results.list(auth(), "REVIEW", 1, null).items().getFirst().matchId());
        var cancelledView = results.list(auth(), "CANCELLED", 1, null).items().getFirst();
        assertEquals(cancelled.getId(), cancelledView.matchId()); assertFalse(cancelledView.canCancel());
        var pending = results.list(auth(), "PENDING", 1, null).items().getFirst();
        assertEquals(future.getId(), pending.matchId()); assertFalse(pending.canCancel());
    }

    @Test void stale_cancel_conflicts_fresh_cancel_succeeds_and_duplicate_retry_is_idempotent() throws Exception {
        var match = match(CREATED, "PENDING", false, MatchMode.CONTROLLED_TEST);
        long old = match.getResultVersion();
        match.result("PENDING", "AWAITING_REPORTS", null, null, 1, 0); matches.flush();
        var login = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject));
        String path = "/api/v1/moderation/capture-tests/" + match.getId() + "/cancel";
        mvc.perform(post(path).param("expectedVersion", Long.toString(old)).with(login).with(csrf())).andExpect(status().isConflict());
        assertNull(match.getCancelledAt());
        mvc.perform(post(path).param("expectedVersion", Long.toString(match.getResultVersion())).with(login).with(csrf())).andExpect(status().isNoContent());
        assertNotNull(match.getCancelledAt());
        mvc.perform(post(path).param("expectedVersion", Long.toString(old)).with(login).with(csrf())).andExpect(status().isNoContent());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.match_result_events WHERE match_id=? AND reason='ASSIGNMENT_CANCELLED'", Integer.class, match.getId()));
        assertFalse(results.list(auth(), "CANCELLED", 50, null).items().stream().filter(m -> m.matchId().equals(match.getId())).findFirst().orElseThrow().canCancel());
    }

    @Test void list_and_cancel_require_current_active_moderator_and_csrf() throws Exception {
        var match = match(CREATED, "PENDING", false, MatchMode.CONTROLLED_TEST);
        var login = oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject));
        mvc.perform(get("/api/v1/moderation/matches")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/moderation/capture-tests/" + match.getId() + "/cancel").with(login)).andExpect(status().isForbidden());
        db.update("UPDATE rbgs.accounts SET account_role='USER' WHERE id=?", moderator.id());
        em.clear();
        mvc.perform(get("/api/v1/moderation/matches").with(login)).andExpect(status().isForbidden());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> results.list(auth(), "ALL", 20, null)).getStatusCode().value());
        db.update("UPDATE rbgs.accounts SET account_role='MODERATOR',account_status='SUSPENDED' WHERE id=?", moderator.id());
        em.clear();
        mvc.perform(get("/api/v1/moderation/matches").with(login)).andExpect(status().isForbidden());
    }

    @Test void filters_limits_and_cursors_are_validated() {
        for (int limit : new int[] {0, 51}) assertEquals(400, assertThrows(ResponseStatusException.class, () -> results.list(auth(), "ALL", limit, null)).getStatusCode().value());
        for (String cursor : new String[] {"", "!invalid", "x".repeat(300), Base64.getUrlEncoder().encodeToString("wrong\nnot-a-uuid".getBytes())})
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> results.list(auth(), "ALL", 20, cursor)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> results.list(auth(), "INVALID", 20, null)).getStatusCode().value());
    }
}
