package io.rbgs.api.characters.selection;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import io.rbgs.api.identity.*;
import io.rbgs.api.seasons.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SelectionIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired SeasonRepository seasons;
    @Autowired SelectionService service;
    @Autowired JdbcTemplate db;
    @MockitoBean SelectionSettings settings;
    @MockitoBean ForeverOwnershipVerifier verifier;
    private Account account;
    private SeasonEntity season;
    private String subject;
    private static final String ISSUER = "https://idp.example";

    @BeforeEach void setup() {
        subject = UUID.randomUUID().toString();
        account = accounts.upsert(ISSUER, subject, "Fixture#1234");
        season = seasons.saveAndFlush(new SeasonEntity("Fixture", SeasonKind.BETA, RatingSubjectType.ACCOUNT));
        when(settings.seasonId()).thenReturn(season.getId());
    }
    @AfterEach void cleanup() {
        db.update("DELETE FROM rbgs.season_selections WHERE season_id = ?", season.getId());
        db.update("DELETE FROM rbgs.character_identities WHERE account_id = ?", account.id());
        seasons.deleteById(season.getId());
        db.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
    }
    private RequestPostProcessor login() {
        return oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject));
    }
    private String request(String version, String name) {
        return """
            {"seasonId":"%s","seasonVersion":%d,"expectedVersion":%s,
             "declaration":{"name":"%s","realm":"Fixture Realm"},"role":"HEALER","captainConsent":false}
            """.formatted(season.getId(), season.getVersion(), version, name);
    }
    private OAuth2AuthenticationToken authentication() {
        var now = Instant.now();
        var token = new OidcIdToken("fixture", now, now.plusSeconds(300), java.util.Map.of("iss", ISSUER, "sub", subject));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(() -> "ROLE_USER"), token), List.of(() -> "ROLE_USER"), "battle-net");
    }

    @Test void noConfiguredSeasonDoesNotCreateOrOpenOne() throws Exception {
        when(settings.seasonId()).thenReturn(null);
        mvc.perform(get("/api/v1/play/context").with(login()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.season").isEmpty())
            .andExpect(jsonPath("$.matchmakingAvailable").value(false));
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("null","Fixture")))
            .andExpect(status().isConflict());
        assertNull(seasons.findById(season.getId()).orElseThrow().getStartedAt());
    }

    @Test void declarationPersistsAcrossRequestsAndKeepsAccountRatingSubject() throws Exception {
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("null","First")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("DECLARED"))
            .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.ratingSubjectId").value(account.id().toString()));
        mvc.perform(get("/api/v1/play/context").with(login())).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.selection.name").value("First"))
            .andExpect(jsonPath("$.selection.captainConsent").value(false));
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("0","Second")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.ratingSubjectId").value(account.id().toString()));
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("0","Stale")))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/play/context").with(login())).andExpect(jsonPath("$.selection.name").value("Second"));
    }

    @Test void csrfAnonymousSuspensionAndValidationAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/play/context")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/selection").with(login()).contentType("application/json").content(request("null","First")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("null"," ")))
            .andExpect(status().isBadRequest());
        db.update("UPDATE rbgs.accounts SET account_status = 'SUSPENDED' WHERE id = ?", account.id());
        mvc.perform(get("/api/v1/play/context").with(login())).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("null","First")))
            .andExpect(status().isForbidden());
    }

    @Test void anotherAccountCannotReadOrOverwriteSelection() throws Exception {
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("null","Private")))
            .andExpect(status().isOk());
        String otherSubject = UUID.randomUUID().toString();
        var other = accounts.upsert(ISSUER, otherSubject, "Other#1234");
        try {
            mvc.perform(get("/api/v1/play/context").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(otherSubject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selection").isEmpty());
            mvc.perform(put("/api/v1/me/selection").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(otherSubject)))
                .with(csrf()).contentType("application/json").content(request("0","Overwrite")))
                .andExpect(status().isConflict());
        } finally { db.update("DELETE FROM rbgs.accounts WHERE id = ?", other.id()); }
    }

    @Test void seasonChangeInvalidatesOldVersionAndDeclarationCannotEnterCharacterMode() throws Exception {
        String previous = request("null","First");
        season.configureRatingSubjectType(RatingSubjectType.CHARACTER);
        season = seasons.saveAndFlush(season);
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(previous))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(request("null","First")))
            .andExpect(status().isBadRequest());
    }

    @Test void verifiedFixtureRequiresOwnershipAndOutagePreservesExistingSelection() throws Exception {
        season.configureRatingSubjectType(RatingSubjectType.CHARACTER);
        season = seasons.saveAndFlush(season);
        UUID id = UUID.randomUUID();
        db.update("""
            INSERT INTO rbgs.character_identities(id, account_id, product, region, source, name, realm,
             provider_namespace, provider_realm_id, provider_character_id)
            VALUES (?, ?, 'WOW_FOREVER','EU','BLIZZARD_VERIFIED','Fixture','Fixture Realm',
             'fixture-forever-eu','fixture-realm','fixture-character')
            """, id, account.id());
        String body = """
            {"seasonId":"%s","seasonVersion":%d,"expectedVersion":null,
             "characterId":"%s","role":"FC","captainConsent":true}
            """.formatted(season.getId(),season.getVersion(),id);
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ratingSubjectId").value(id.toString()));
        verify(verifier).requireOwnership(eq(account.id()),any(),any());
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Fixture outage")).when(verifier).requireOwnership(any(),any(),any());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(body.replace("\"expectedVersion\":null","\"expectedVersion\":0")))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/play/context").with(login())).andExpect(jsonPath("$.selection.characterId").value(id.toString()));
        assertEquals(RatingSubjectType.CHARACTER, seasons.findById(season.getId()).orElseThrow().getRatingSubjectType());
    }

    @Test void simultaneousFirstSelectionsHaveExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var command = new SelectionRequest(season.getId(),season.getVersion(),null,null,
                new SelectionRequest.Declaration("Fixture","Fixture Realm"),MatchRole.DPS,false);
            Callable<Integer> write = () -> {
                ready.countDown();
                assertTrue(start.await(10,TimeUnit.SECONDS));
                try { service.save(authentication(),command);return 200; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            };
            var a = executor.submit(write);var b = executor.submit(write);
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rbgs.season_selections WHERE account_id = ? AND season_id = ?",Integer.class,account.id(),season.getId()));
        }
    }
}
