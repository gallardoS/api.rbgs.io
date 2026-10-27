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
class AnniversaryCaptureTestTests {
    private static final String PATH = "/api/v1/moderation/capture-tests/anniversary";
    private static final String ISSUER = "https://anniversary-test.example";
    @Autowired MockMvc mvc;
    @Autowired CaptureJson json;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entityManager;
    @MockitoBean WowCharacterService characters;
    @MockitoBean OAuth2AuthorizedClientRepository clients;
    String subject;
    UUID accountId;

    @BeforeEach void setup() {
        subject = UUID.randomUUID().toString();
        accountId = accounts.upsert(ISSUER, subject, "AnniversaryFixture").id();
        db.update("UPDATE rbgs.accounts SET account_role='MODERATOR' WHERE id=?", accountId);
        entityManager.clear();
        when(characters.currentAnniversaryTestCharacters(any())).thenReturn(profile());
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
                new WowAccountProfile.WowCharacter(42, "OwnedPlayer", new WowAccountProfile.Realm(6409, "FixtureRealm", "fixture-realm"),
                        null, null, null, 70, null, null, "profile-classicann-eu", null, null)))));
    }
    private Map<String,Object> reference() { return new LinkedHashMap<>(Map.of("namespace", "profile-classicann-eu", "wowAccountId", 7, "realmId", 6409, "characterId", 42)); }
    private String body(Map<String,Object> reference) { return json.encode(Map.of("armMinutes", 60, "character", reference)); }
    private int count() { return db.queryForObject("SELECT COUNT(*) FROM rbgs.matches WHERE created_by=?", Integer.class, accountId); }

    @Test void listingRequiresCurrentModeratorAndNeverExposesAnAlternateAccountsProfile() throws Exception {
        mvc.perform(get(PATH+"/characters")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH+"/characters").with(login())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.wow_accounts[0].characters[0].namespace").value("profile-classicann-eu"));
        db.update("UPDATE rbgs.accounts SET account_role='USER' WHERE id=?", accountId); entityManager.clear();
        mvc.perform(get(PATH+"/characters").with(login())).andExpect(status().isForbidden());
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

    @Test void creationFreezesServerResolvedCharacterAndProviderProvenance() throws Exception {
        var result = mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.testKind").value("CAPTURE_ONLY")).andReturn();
        UUID match = CaptureJson.uuid(json.parse(result.getResponse().getContentAsByteArray()).get("matchId"));
        String roster = db.queryForObject("SELECT expected_roster FROM rbgs.matches WHERE id=?", String.class, match);
        var rows = (List<?>)json.parse(("{\"rows\":"+roster+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("rows");
        var player = CaptureJson.object(rows.getFirst());
        assertEquals(Map.of("name","OwnedPlayer","realm","FixtureRealm"), player.get("character"));
        assertEquals("profile-classicann-eu", CaptureJson.object(player.get("providerReference")).get("namespace"));
        assertEquals("TBC_ANNIVERSARY_WSG_TEST", db.queryForObject("SELECT profile FROM rbgs.matches WHERE id=?", String.class, match));
        assertEquals("CAPTURE_ONLY", db.queryForObject("SELECT evidence_policy FROM rbgs.matches WHERE id=?", String.class, match));
        assertNull(player.get("assignedTeamKey"));
    }

    @Test void disappearedCharacterAndBlizzardFailureCannotCreateADeclaredFallback() throws Exception {
        when(characters.currentAnniversaryTestCharacters(any())).thenReturn(new WowAccountProfile(List.of()));
        mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference())))
                .andExpect(status().isUnprocessableContent());
        when(characters.currentAnniversaryTestCharacters(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Blizzard is unavailable"));
        mvc.perform(post(PATH).with(login()).with(csrf()).contentType("application/json").content(body(reference()))).andExpect(status().isBadGateway());
        mvc.perform(get(PATH+"/characters").with(login())).andExpect(status().isBadGateway());
        assertEquals(0, count());
    }

    @Test void legacyCreationCannotBypassRequiredAnniversarySelection() throws Exception {
        var player = new LinkedHashMap<String,Object>(); player.put("accountId",accountId.toString()); player.put("teamKey",null);
        player.put("character",Map.of("name","DeclaredPlayer","realm","DeclaredRealm"));
        mvc.perform(post("/api/v1/moderation/capture-tests").with(login()).with(csrf()).contentType("application/json")
                .content(json.encode(Map.of("armMinutes",60,"testKind","CAPTURE_ONLY","expectedMapProfile","TBC_ANNIVERSARY_WSG_TEST","roster",List.of(player)))))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0,count());
    }
}
