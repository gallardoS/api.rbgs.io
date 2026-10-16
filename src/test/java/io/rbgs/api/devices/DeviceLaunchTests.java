package io.rbgs.api.devices;

import io.rbgs.api.identity.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceLaunchTests {
    @Autowired DeviceLaunchService launches;
    @Autowired DeviceService connections;
    @Autowired DeviceRepository devices;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    static final String ISSUER="https://idp.example";
    static final String PROOF="b".repeat(64);
    String subject;
    Account account;
    List<UUID> fixtures;

    @BeforeEach void setup() {
        subject=UUID.randomUUID().toString(); account=accounts.upsert(ISSUER,subject,"LaunchFixture");
        fixtures=new ArrayList<>(List.of(account.id()));
    }
    @AfterEach void cleanup() {
        for(UUID id:fixtures) {
            db.update("DELETE FROM rbgs.device_launches WHERE account_id=?",id);
            db.update("DELETE FROM rbgs.devices WHERE account_id=?",id);
            db.update("DELETE FROM rbgs.accounts WHERE id=?",id);
        }
    }
    OAuth2AuthenticationToken auth(String sub) {
        var token=new OidcIdToken("fixture",Instant.now(),Instant.now().plusSeconds(300),Map.of("iss",ISSUER,"sub",sub));
        var authorities=List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities,token),authorities,"battle-net");
    }
    DeviceLaunchService.Launch start() {return launches.start(auth(subject),true);}
    DeviceService.Token claim(DeviceLaunchService.Launch l) {return launches.claim(l.launchSecret(),PROOF,null);}
    void fails(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code,assertThrows(ResponseStatusException.class,action).getStatusCode().value());
    }
    String body(DeviceLaunchService.Launch l) {return "{\"launchSecret\":\""+l.launchSecret()+"\",\"clientSecret\":\""+PROOF+"\"}";}

    @Test void browserRequiresAuthenticationCsrfAndExplicitCaptureConsent() throws Exception {
        mvc.perform(post("/api/v1/device-launches").with(csrf()).contentType("application/json").content("{\"acceptCapturePermissions\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/device-launches").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject)))
                .contentType("application/json").content("{\"acceptCapturePermissions\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-launches").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf())
                .contentType("application/json").content("{\"acceptCapturePermissions\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-launches").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf())
                .contentType("application/json").content("{\"acceptCapturePermissions\":true}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.launchSecret").isString()).andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }
    @Test void nativeClaimNeverAcceptsBrowserRequestsOrCookieAuthentication() throws Exception {
        var l=start();
        for(String header:List.of("Origin","Sec-Fetch-Site"))
            mvc.perform(post("/api/v1/companion/connect").header(header,"same-origin").contentType("application/json").content(body(l)))
                    .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/companion/connect").contentType("application/json").content(body(l).replace(l.launchSecret(),"a".repeat(43))))
                .andExpect(status().isGone());
        mvc.perform(post("/api/v1/companion/connect").contentType("application/json").content(body(l)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value(DeviceService.CAPTURE_SCOPE));
    }
    @Test void tokenStaysHashedAndProgressOnlyCompletesAfterNativeHeartbeat() throws Exception {
        var l=start();assertEquals("PENDING",launches.progress(auth(subject),l.id()).status());
        var token=claim(l);
        assertEquals(DeviceService.CAPTURE_SCOPE,token.scope());
        assertEquals(DeviceService.hash(token.token()),devices.findById(token.deviceId()).orElseThrow().getTokenHash());
        assertEquals("CONNECTING",launches.progress(auth(subject),l.id()).status());
        assertNull(launches.progress(auth(subject),l.id()).deviceId());
        connections.heartbeat("Bearer "+token.token());
        assertEquals(token.deviceId(),launches.progress(auth(subject),l.id()).deviceId());
        mvc.perform(get("/api/v1/device-launches/"+l.id()).with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAIRED"))
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.launchSecret").doesNotExist())
                .andExpect(jsonPath("$.claimHash").doesNotExist());
    }
    @Test void lostNativeResponseCanBeRecoveredOnlyWithSameRandomProof() {
        var l=start();var first=claim(l);var retry=claim(l);
        assertEquals(first.token(),retry.token());assertEquals(first.deviceId(),retry.deviceId());assertEquals(first.scope(),retry.scope());
        assertEquals(1,devices.countByAccountIdAndRevokedAtIsNullAndExpiresAtAfter(account.id(),Instant.now()));
        fails(410,()->launches.claim(l.launchSecret(),"c".repeat(64),null));
        connections.revoke(auth(subject),first.deviceId());
        fails(410,()->claim(l));
    }
    @Test void removingAnInactiveDevicePreservesItsLaunchReferenceAndDoesNotPermitReplay() {
        var launch=start();var token=claim(launch);
        connections.revoke(auth(subject),token.deviceId());
        connections.removeInactive(auth(subject),token.deviceId());
        assertTrue(connections.list(auth(subject)).isEmpty());
        assertEquals(token.deviceId(),db.queryForObject("SELECT device_id FROM rbgs.device_launches WHERE id=?",UUID.class,launch.id()));
        fails(410,()->claim(launch));
        fails(410,()->launches.progress(auth(subject),launch.id()));
        var replacement=claim(start());assertNotEquals(token.deviceId(),replacement.deviceId());
        assertEquals(replacement.deviceId(),connections.list(auth(subject)).getFirst().id());
    }

    @Test void existingSameAccountIsReusedAndStatusOnlyScopeIsUpgraded() {
        String raw=DeviceService.secret();
        var device=devices.save(new DeviceEntity(account.id(),DeviceService.hash(raw),Instant.now(),DeviceService.SCOPE));
        var l=start();var result=launches.claim(l.launchSecret(),PROOF,"Bearer "+raw);
        assertNull(result.token());assertEquals(device.getId(),result.deviceId());
        assertEquals(DeviceService.CAPTURE_SCOPE,connections.status("Bearer "+raw).scope());
        assertEquals(result,launches.claim(l.launchSecret(),PROOF,"Bearer "+raw));
        assertEquals(1,devices.findByAccountIdOrderByCreatedAtDesc(account.id()).size());
    }
    @Test void differentAccountCannotSilentlyReplaceAnExistingConnection() {
        String otherSubject=UUID.randomUUID().toString();var other=accounts.upsert(ISSUER,otherSubject,"OtherFixture");fixtures.add(other.id());
        String raw=DeviceService.secret();devices.save(new DeviceEntity(other.id(),DeviceService.hash(raw),Instant.now(),DeviceService.SCOPE));
        var l=start();
        fails(409,()->launches.claim(l.launchSecret(),PROOF,"Bearer "+raw));
        assertEquals(other.id(),connections.status("Bearer "+raw).accountId());
        fails(410,()->launches.progress(auth(otherSubject),l.id()));
        assertEquals("PENDING",launches.progress(auth(subject),l.id()).status());
    }
    @Test void expiredTicketAndInactiveAccountCannotIssueCredentials() {
        var expired=start();db.update("UPDATE rbgs.device_launches SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),expired.id());
        fails(410,()->claim(expired));fails(410,()->launches.progress(auth(subject),expired.id()));
        var l=start();db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?",account.id());
        fails(410,()->claim(l));
        assertTrue(devices.findByAccountIdOrderByCreatedAtDesc(account.id()).isEmpty());
    }
    @Test void deviceLimitAndRevokedBearerAreEnforced() {
        var l=start();
        fails(401,()->launches.claim(l.launchSecret(),PROOF,"Bearer "+"a".repeat(43)));
        for(int i=0;i<5;i++)devices.save(new DeviceEntity(account.id(),DeviceService.hash(DeviceService.secret()),Instant.now(),DeviceService.SCOPE));
        fails(409,()->claim(l));
    }
    @Test void concurrentClaimsFromDifferentNativeClientsIssueExactlyOneCredential() throws Exception {
        var l=start();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(String proof:List.of(PROOF,"c".repeat(64)))results.add(pool.submit(()->{
                gate.await();try{launches.claim(l.launchSecret(),proof,null);return true;}
                catch(ResponseStatusException e){assertEquals(410,e.getStatusCode().value());return false;}
            }));
            gate.countDown();int issued=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS))issued++;
            assertEquals(1,issued);
        }
        assertEquals(1,devices.findByAccountIdOrderByCreatedAtDesc(account.id()).size());
    }
}
