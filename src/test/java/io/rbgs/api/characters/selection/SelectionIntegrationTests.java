package io.rbgs.api.characters.selection;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import io.rbgs.api.identity.*;
import io.rbgs.api.devices.DeviceService;
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
    @Autowired DeviceService devices;
    @MockitoBean SelectionSettings settings;
    @MockitoBean ForeverOwnershipVerifier verifier;
    private Account account;
    private SeasonEntity season;
    private String subject;
    private UUID deviceId;
    private static final String ISSUER = "https://idp.example";

    @BeforeEach void setup() {
        subject = UUID.randomUUID().toString();
        account = accounts.upsert(ISSUER, subject, "Fixture#1234");
        season = seasons.saveAndFlush(new SeasonEntity("Fixture", SeasonKind.BETA, RatingSubjectType.ACCOUNT));
        when(settings.seasonId()).thenReturn(season.getId());
        var pairing = devices.start();
        devices.approve(authentication(), pairing.code());
        deviceId = devices.exchange(pairing.pollingSecret()).deviceId();
    }
    @AfterEach void cleanup() {
        db.update("DELETE FROM rbgs.season_selections WHERE season_id = ?", season.getId());
        db.update("DELETE FROM rbgs.character_identities WHERE account_id = ?", account.id());
        seasons.deleteById(season.getId());
        db.update("DELETE FROM rbgs.device_pairings WHERE account_id = ?", account.id());
        db.update("DELETE FROM rbgs.devices WHERE account_id = ?", account.id());
        db.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
    }
    private RequestPostProcessor login() {
        return oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject));
    }
    private String request(String version, String name) {
        return """
            {"seasonId":"%s","seasonVersion":%d,"expectedVersion":%s,
             "declaration":{"name":"%s","realm":"Normal"},"role":"HEALER","captainConsent":false,"companionDeviceId":"%s"}
            """.formatted(season.getId(), season.getVersion(), version, name, deviceId);
    }
    private OAuth2AuthenticationToken authentication() {
        var now = Instant.now();
        var token = new OidcIdToken("fixture", now, now.plusSeconds(300), java.util.Map.of("iss", ISSUER, "sub", subject));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(() -> "ROLE_USER"), token), List.of(() -> "ROLE_USER"), "battle-net");
    }

    @Test void premadeCharacterDefaultsSoloRoleToDpsWithoutRequiringAChoice() throws Exception {
        String body = request("null", "Premade").replace("\"role\":\"HEALER\",", "").replace("\"captainConsent\":false,", "");
        mvc.perform(put("/api/v1/me/premade-selection").with(login()).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("DPS")).andExpect(jsonPath("$.name").value("Premade"));
        assertEquals(MatchRole.DPS,service.context(authentication()).selection().role());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(body.replace("\"expectedVersion\":null", "\"expectedVersion\":0")))
            .andExpect(status().isBadRequest());
    }
    @Test void soloRoleCannotBeClearedAndPremadeCharacterUpdatesPreserveIt() throws Exception {
        var initial=service.save(authentication(),new SelectionRequest(season.getId(),season.getVersion(),null,null,
            new SelectionRequest.Declaration("Dps","Normal"),MatchRole.DPS,false,deviceId));
        for(String body:List.of(request(String.valueOf(initial.version()),"Dps").replace("\"role\":\"HEALER\"","\"role\":null"),
                request(String.valueOf(initial.version()),"Dps").replace("\"role\":\"HEALER\",","")))
            mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        assertEquals(MatchRole.DPS,service.context(authentication()).selection().role());
        assertEquals(initial.version(),service.context(authentication()).selection().version());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->
            db.update("UPDATE rbgs.season_selections SET match_role=NULL WHERE account_id=?",account.id()));
        assertEquals(MatchRole.DPS,service.context(authentication()).selection().role());
    }
    @Test void premadeCharacterSavePreservesCompetitiveRoleConsentAndVersions() throws Exception {
        var first = service.save(authentication(), new SelectionRequest(season.getId(), season.getVersion(), null, null,
            new SelectionRequest.Declaration("Solo", "Normal"), MatchRole.FC, true, deviceId));
        String body = request(String.valueOf(first.version()), "Premade").replace("\"role\":\"HEALER\",", "").replace("\"captainConsent\":false,", "");
        mvc.perform(put("/api/v1/me/premade-selection").with(login()).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("FC")).andExpect(jsonPath("$.captainConsent").value(true));
        mvc.perform(put("/api/v1/me/premade-selection").with(login()).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isConflict());
    }
    @Test void premadeCharacterSaveStillRequiresAuthenticationCsrfSeasonAndDevice() throws Exception {
        String body = request("null", "Premade").replace("\"role\":\"HEALER\",", "").replace("\"captainConsent\":false,", "");
        mvc.perform(put("/api/v1/me/premade-selection").with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/premade-selection").with(login()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/me/premade-selection").with(login()).with(csrf()).contentType("application/json")
            .content(body.replace(deviceId.toString(), UUID.randomUUID().toString()))).andExpect(status().isPreconditionFailed());
        when(settings.seasonId()).thenReturn(null);
        mvc.perform(put("/api/v1/me/premade-selection").with(login()).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict());
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

    @Test void declarationAcceptsClassicRealmAndRejectsInvalidText() throws Exception {
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf())
            .contentType("application/json").content(request("null", "Fixture").replace("Normal", "Classic Realm")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.realm").value("Classic Realm"))
            .andExpect(jsonPath("$.source").value("DECLARED"));
        for (String realm : List.of("", " ", "x".repeat(101))) {
            mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf())
                .contentType("application/json").content(request("0", "Fixture").replace("Normal", realm)))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/play/context").with(login()))
            .andExpect(jsonPath("$.selection.realm").value("Classic Realm"))
            .andExpect(jsonPath("$.selection.version").value(0));
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
            VALUES (?, ?, 'WOW_FOREVER','EU','BLIZZARD_VERIFIED','Fixture','Normal',
             'fixture-forever-eu','fixture-realm','fixture-character')
            """, id, account.id());
        String body = """
            {"seasonId":"%s","seasonVersion":%d,"expectedVersion":null,
             "characterId":"%s","role":"FC","captainConsent":true,"companionDeviceId":"%s"}
            """.formatted(season.getId(),season.getVersion(),id,deviceId);
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
                new SelectionRequest.Declaration("Fixture","Normal"),MatchRole.DPS,false,deviceId);
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

    @Test void missingMalformedAndUnknownCompanionCannotBypassTheButton() throws Exception {
        String body = request("null", "Fixture");
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(body.replace(",\"companionDeviceId\":\"" + deviceId + "\"", "")))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(body.replace(deviceId.toString(), "not-a-uuid")))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(body.replace(deviceId.toString(), UUID.randomUUID().toString())))
            .andExpect(status().isPreconditionFailed());
        assertNoSelectionWrites();
    }

    @Test void companionOwnedByAnotherAccountCannotAuthorizeSelection() throws Exception {
        String otherSubject = UUID.randomUUID().toString();
        var other = accounts.upsert(ISSUER, otherSubject, "Other#1234");
        try {
            mvc.perform(put("/api/v1/me/selection")
                .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(otherSubject))).with(csrf())
                .contentType("application/json").content(request("null", "Stolen device")))
                .andExpect(status().isPreconditionFailed());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM rbgs.season_selections WHERE account_id = ?", Integer.class, other.id()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM rbgs.character_identities WHERE account_id = ?", Integer.class, other.id()));
        } finally { db.update("DELETE FROM rbgs.accounts WHERE id = ?", other.id()); }
        assertNoSelectionWrites();
    }

    @Test void revokedSelectedCompanionCannotBeReplacedByAnotherLinkedDevice() throws Exception {
        var pairing = devices.start();
        devices.approve(authentication(), pairing.code());
        devices.exchange(pairing.pollingSecret());
        devices.revoke(authentication(), deviceId);
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(request("null", "Fixture"))).andExpect(status().isPreconditionFailed());
        assertNoSelectionWrites();
    }

    @Test void expiryAfterFirstSaveRejectsUpdatesAndPreservesExistingSelection() throws Exception {
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(request("null", "Saved"))).andExpect(status().isOk());
        db.update("UPDATE rbgs.devices SET expires_at = now() - interval '1 second' WHERE id = ?", deviceId);
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(request("0", "Must not save"))).andExpect(status().isPreconditionFailed());
        mvc.perform(get("/api/v1/play/context").with(login()))
            .andExpect(jsonPath("$.selection.name").value("Saved"))
            .andExpect(jsonPath("$.selection.version").value(0));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM rbgs.character_identities WHERE account_id = ?", Integer.class, account.id()));
    }

    @Test void linkageIsCheckedOnTheServerWithoutRequiringARecentHeartbeat() throws Exception {
        assertFalse(devices.list(authentication()).getFirst().connected());
        mvc.perform(put("/api/v1/me/selection").with(login()).with(csrf()).contentType("application/json")
            .content(request("null", "Linked"))).andExpect(status().isOk());
    }

    private void assertNoSelectionWrites() {
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM rbgs.season_selections WHERE account_id = ?", Integer.class, account.id()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM rbgs.character_identities WHERE account_id = ?", Integer.class, account.id()));
    }
}
