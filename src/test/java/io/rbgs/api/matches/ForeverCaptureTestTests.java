package io.rbgs.api.matches;

import io.rbgs.api.characters.WowCharacterService;
import io.rbgs.api.characters.dto.WowAccountProfile;
import io.rbgs.api.evidence.CaptureJson;
import io.rbgs.api.identity.AccountService;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "rbgs.capture.controlled-enabled=true")
@AutoConfigureMockMvc
@Transactional
class ForeverCaptureTestTests {
    private static final String PATH = "/api/v1/moderation/capture-tests/forever";
    private static final String ISSUER = "https://forever-wsg-test.example";
    @Autowired MockMvc mvc;
    @Autowired CaptureJson json;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entityManager;
    @Autowired io.rbgs.api.evidence.CaptureService captures;
    @MockitoBean WowCharacterService characters;
    @MockitoBean OAuth2AuthorizedClientRepository clients;
    String subject;
    UUID accountId;

    @BeforeEach void setup() {
        subject = UUID.randomUUID().toString();
        accountId = accounts.upsert(ISSUER, subject, "ForeverFixture").id();
        db.update("UPDATE rbgs.accounts SET account_role='MODERATOR' WHERE id=?", accountId);
        entityManager.clear();
        when(characters.currentForeverTestCharacters(any())).thenReturn(profile());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor login() {
        var registration = ClientRegistration.withRegistrationId("battle-net").clientId("fixture")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://example.com/callback")
                .authorizationUri("https://example.com/authorize").tokenUri("https://example.com/token")
                .jwkSetUri("https://example.com/keys").build();
        return oidcLogin().clientRegistration(registration).idToken(t -> t.issuer(ISSUER).subject(subject));
    }
    private WowAccountProfile profile() {
        return new WowAccountProfile(List.of(new WowAccountProfile.WowAccount(7, List.of(
                new WowAccountProfile.WowCharacter(42, "OwnedPlayer Surname", new WowAccountProfile.Realm(6409, "FixtureRealm", "fixture-realm"),
                        null, null, null, 17, null, null, "profile-forever-eu", null, null)))));
    }
    private Map<String,Object> reference() { return new LinkedHashMap<>(Map.of("namespace", "profile-forever-eu", "wowAccountId", 7, "realmId", 6409, "characterId", 42)); }
    private String body(Map<String,Object> reference) { return json.encode(Map.of("armMinutes", 60, "character", reference)); }
    private int count() { return db.queryForObject("SELECT COUNT(*) FROM rbgs.matches WHERE created_by=?", Integer.class, accountId); }

    @Test void listingRequiresCurrentModeratorAndNeverExposesAnAlternateAccountsProfile() throws Exception {
        mvc.perform(get(PATH+"/characters")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH+"/characters").with(login())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.wow_accounts[0].characters[0].namespace").value("profile-forever-eu"));
        db.update("UPDATE rbgs.accounts SET account_role='USER' WHERE id=?", accountId); entityManager.clear();
        mvc.perform(get(PATH+"/characters").with(login())).andExpect(status().isForbidden());
    }

    @Test void testerCanCreateAnOwnedTestThroughTesterRoutesOnly() throws Exception {
        String testerPath = "/api/v1/tester/capture-tests/forever";
        db.update("UPDATE rbgs.accounts SET account_role='TESTER' WHERE id=?", accountId);
        entityManager.clear();
        mvc.perform(get(testerPath + "/characters").with(login())).andExpect(status().isOk());
        mvc.perform(post(testerPath).with(login()).contentType("application/json").content(body(reference())))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
        mvc.perform(post(testerPath).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isUnprocessableContent());
        db.update("""
                INSERT INTO rbgs.play_characters(account_id,namespace,wow_account_id,realm_id,character_id,region,name,realm)
                VALUES (?, 'profile-forever-eu',7,6409,42,'EU','OwnedPlayer Surname','FixtureRealm')
                """, accountId);
        var changed = reference();
        changed.put("characterId", 999);
        mvc.perform(post(testerPath).with(login()).with(csrf()).contentType("application/json").content(body(changed)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post(testerPath).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignments[0].accountId").value(accountId.toString()));
        assertEquals(1, count());
    }

    @Test void creationRequiresCsrfAndAnUnambiguousOwnedProviderReference() throws Exception {
        mvc.perform(post(PATH).with(login()).contentType("application/json").content(body(reference()))).andExpect(status().isForbidden());
        for (String field : List.of("namespace", "wowAccountId", "realmId", "characterId")) {
            var ref = reference(); ref.put(field, field.equals("namespace") ? "profile-classic-eu" : 999);
            mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(ref)))
                    .andExpect(status().isUnprocessableContent());
        }
        var ref = reference(); ref.put("name", "ForgedPlayer");
        mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(ref))).andExpect(status().isUnprocessableContent());
        assertEquals(0, count());
    }

    @Test void testerDiagnosticTestMustUseThePlayCharacter() throws Exception {
        db.update("UPDATE rbgs.accounts SET account_role='TESTER' WHERE id=?", accountId);
        entityManager.clear();
        String path = "/api/v1/tester/capture-tests";
        var player = new LinkedHashMap<String,Object>();
        player.put("accountId", accountId.toString());
        player.put("teamKey", null);
        player.put("character", Map.of("name", "OtherPlayer", "realm", "FixtureRealm"));
        var request = Map.of("armMinutes", 60, "testKind", "CAPTURE_ONLY",
                "expectedMapProfile", "DARKSPEAR_DIAGNOSTIC", "roster", List.of(player));
        mvc.perform(post(path).with(login()).with(csrf()).contentType("application/json").content(json.encode(request)))
                .andExpect(status().isUnprocessableContent());
        db.update("""
                INSERT INTO rbgs.play_characters(account_id,namespace,wow_account_id,realm_id,character_id,region,name,realm)
                VALUES (?, 'profile-classic1x-eu',7,6409,42,'EU','PlayPlayer','FixtureRealm')
                """, accountId);
        mvc.perform(post(path).with(login()).with(csrf()).contentType("application/json").content(json.encode(request)))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, count());
        player.put("character", Map.of("name", "PlayPlayer", "realm", "FixtureRealm"));
        mvc.perform(post(path).with(login()).with(csrf()).contentType("application/json").content(json.encode(request)))
                .andExpect(status().isOk());
        assertEquals(1, count());
    }

    @Test void creationFreezesServerResolvedCharacterAndProviderProvenance() throws Exception {
        var result = mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.testKind").value("CAPTURE_ONLY")).andReturn();
        UUID match = CaptureJson.uuid(json.parse(result.getResponse().getContentAsByteArray()).get("matchId"));
        String roster = db.queryForObject("SELECT expected_roster FROM rbgs.matches WHERE id=?", String.class, match);
        var rows = (List<?>)json.parse(("{\"rows\":"+roster+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("rows");
        var player = CaptureJson.object(rows.getFirst());
        assertEquals("OwnedPlayer Surname", CaptureJson.object(player.get("character")).get("name"));
        assertNull(CaptureJson.object(player.get("character")).get("realm"));
        assertEquals("profile-forever-eu", CaptureJson.object(player.get("providerReference")).get("namespace"));
        assertEquals("FOREVER_WSG_TEST", db.queryForObject("SELECT profile FROM rbgs.matches WHERE id=?", String.class, match));
        assertEquals("CAPTURE_ONLY", db.queryForObject("SELECT evidence_policy FROM rbgs.matches WHERE id=?", String.class, match));
        assertNull(player.get("assignedTeamKey"));
    }

    @Test void disappearedCharacterAndBlizzardFailureCannotCreateADeclaredFallback() throws Exception {
        when(characters.currentForeverTestCharacters(any())).thenReturn(new WowAccountProfile(List.of()));
        mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isUnprocessableContent());
        when(characters.currentForeverTestCharacters(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Blizzard is unavailable"));
        mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference()))).andExpect(status().isBadGateway());
        mvc.perform(get(PATH+"/characters").with(login())).andExpect(status().isBadGateway());
        assertEquals(0, count());
    }

    @Test void foreverAssignmentStoresFlagsAndRecoversReceiptWithoutRatedAdmission() throws Exception {
        var response = mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isOk()).andReturn();
        var created = json.parse(response.getResponse().getContentAsByteArray());
        UUID capture = CaptureJson.uuid(CaptureJson.object(((List<?>)created.get("assignments")).getFirst()).get("captureId"));
        assertThrows(ResponseStatusException.class, () -> captures.issue(accountId, capture, true, "TBC_ANNIVERSARY_WSG_TEST"));
        var issued = captures.issue(accountId, capture, true, "TBC_ANNIVERSARY_WSG_TEST,FOREVER_WSG_TEST");
        var descriptor = CaptureJson.object(issued.get("assignment"));
        assertEquals("CAPTURE_ONLY", descriptor.get("testKind"));
        assertNull(descriptor.get("assignedTeamKey"));
        Map<String,Object> report;
        try (var stream = getClass().getResourceAsStream("/capture/forever-wsg-wire.sanitized.json")) {
            report = json.parse(stream.readAllBytes());
        }
        report.put("captureId", capture.toString());
        report.put("matchId", descriptor.get("matchId").toString());
        report.put("assignmentId", descriptor.get("assignmentId").toString());
        report.put("assignmentCode", issued.get("assignmentCode"));
        UUID device = UUID.randomUUID();
        db.update("INSERT INTO rbgs.devices(id,account_id,token_hash,created_at,expires_at,granted_scope,last_seen_at) VALUES(?,?,?,now(),now()+interval '1 day','companion:status companion:assignments evidence:write',now())",
                device, accountId, UUID.randomUUID().toString());
        var receipt = captures.ingest(new io.rbgs.api.devices.DeviceService.Status(device, accountId, "evidence:write", "Fixture"), report);
        assertTrue(receipt.durablyStored());
        assertEquals(receipt.evidenceId(), captures.recover(accountId, receipt.assignmentId(), capture, receipt.captureRevision()).evidenceId());
        var payload = json.parse(db.queryForObject("SELECT redacted_payload FROM rbgs.capture_evidence WHERE id=?", String.class, receipt.evidenceId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var rows = (List<?>)CaptureJson.object(payload.get("capture")).get("roster");
        assertEquals(19, rows.size());
        assertEquals(3L, rows.stream().map(CaptureJson::object).mapToLong(r -> CaptureJson.number(r.get("flagsCaptured"))).sum());
        assertEquals("CAPTURE_ONLY_NO_EXPECTED_TEAMS", db.queryForObject("SELECT review_reason FROM rbgs.capture_evidence WHERE id=?", String.class, receipt.evidenceId()));
        assertThrows(IllegalArgumentException.class, () -> new MatchEntity(accountId, java.time.Instant.now(), java.time.Instant.now(), "[]",
                MatchMode.MATCHMADE, MatchEvidencePolicy.FULL_ROSTER, "FOREVER_WSG_TEST", java.time.Instant.now()));
    }

    @Test void legacyCreationCannotBypassRequiredForeverSelection() throws Exception {
        var player = new LinkedHashMap<String,Object>(); player.put("accountId",accountId.toString()); player.put("teamKey",null);
        player.put("character",Map.of("name","DeclaredPlayer","realm","DeclaredRealm"));
        mvc.perform(post("/api/v1/moderation/capture-tests").with(login()).with(csrf()).contentType("application/json")
                .content(json.encode(Map.of("armMinutes",60,"testKind","CAPTURE_ONLY","expectedMapProfile","FOREVER_WSG_TEST","roster",List.of(player)))))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0,count());
    }
}
