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
class DevicePairingTests {
    @Autowired DeviceService service;
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    String subject;
    Account account;
    List<UUID> pairingIds;
    static final String ISSUER = "https://idp.example";

    @BeforeEach void setup() {
        subject = UUID.randomUUID().toString();
        account = accounts.upsert(ISSUER, subject, "DeviceFixture");
        pairingIds = new ArrayList<>();
    }
    @AfterEach void cleanup() {
        for (UUID id : pairingIds) db.update("DELETE FROM rbgs.device_pairings WHERE id = ?", id);
        db.update("DELETE FROM rbgs.devices WHERE account_id = ?", account.id());
        db.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
    }
    DeviceService.Pairing start() {
        var p = service.start();
        pairingIds.add(db.queryForObject("SELECT id FROM rbgs.device_pairings WHERE polling_hash = ?", UUID.class, DeviceService.hash(p.pollingSecret())));
        return p;
    }
    OAuth2AuthenticationToken auth() {
        Instant now = Instant.now();
        var token = new OidcIdToken("fixture", now, now.plusSeconds(300), Map.of("iss", ISSUER, "sub", subject));
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, token), authorities, "battle-net");
    }
    DeviceService.Token paired() {
        var p = start(); service.approve(auth(), p.code()); return service.exchange(p.pollingSecret());
    }

    @Test void statusUsesCurrentDisplayNameForTheCredentialOwner() throws Exception {
        var token = paired();
        accounts.upsert(ISSUER, subject, "UpdatedBattleTag#1234");
        mvc.perform(get("/api/v1/companion/status").header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account.id().toString()))
                .andExpect(jsonPath("$.accountName").value("UpdatedBattleTag#1234"));
    }

    @Test void pairingRequiresApprovalThenIssuesOneScopedHashedCredential() throws Exception {
        var p = start();
        assertEquals("PENDING", service.exchange(p.pollingSecret()).status());
        mvc.perform(post("/api/v1/device-pairings/approve").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject)))
                .contentType("application/json").content("{\"code\":\"" + p.code() + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-pairings/approve").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject))).with(csrf())
                .contentType("application/json").content("{\"code\":\"" + p.code() + "\"}"))
                .andExpect(status().isNoContent());
        var token = service.exchange(p.pollingSecret());
        assertEquals(DeviceService.SCOPE, token.scope());
        assertEquals(DeviceService.hash(token.token()), db.queryForObject("SELECT token_hash FROM rbgs.devices WHERE id = ?", String.class, token.deviceId()));
        assertThrows(ResponseStatusException.class, () -> service.exchange(p.pollingSecret()));
        assertThrows(ResponseStatusException.class, () -> service.approve(auth(), p.code()));
        mvc.perform(get("/api/v1/companion/status").header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(account.id().toString())).andExpect(jsonPath("$.accountName").value("DeviceFixture"));
        mvc.perform(get("/api/v1/devices/me").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].token").doesNotExist()).andExpect(jsonPath("$[0].name").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void expiredCodesAndUnknownPollingSecretsCannotBind() {
        var p = start();
        db.update("UPDATE rbgs.device_pairings SET expires_at = ? WHERE polling_hash = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), DeviceService.hash(p.pollingSecret()));
        assertThrows(ResponseStatusException.class, () -> service.approve(auth(), p.code()));
        assertThrows(ResponseStatusException.class, () -> service.exchange(p.pollingSecret()));
        assertThrows(ResponseStatusException.class, () -> service.exchange("unknown"));
        assertTrue(service.list(auth()).isEmpty());
    }

    @Test void browserApprovalIsSeparateSingleUseAndNeverExposesTheCredential() throws Exception {
        var p=start();assertEquals(43,p.approvalSecret().length());
        assertEquals(DeviceService.hash(p.approvalSecret()),db.queryForObject("SELECT approval_hash FROM rbgs.device_pairings WHERE polling_hash=?",String.class,DeviceService.hash(p.pollingSecret())));
        assertEquals("PENDING",service.browserPairing(auth(),p.approvalSecret(),false,false).status());
        assertEquals("PENDING",service.exchange(p.pollingSecret()).status());
        assertEquals(410,assertThrows(ResponseStatusException.class,()->service.exchange(p.approvalSecret())).getStatusCode().value());
        mvc.perform(post("/api/v1/device-pairings/browser/approve").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).contentType("application/json").content("{\"approvalSecret\":\""+p.approvalSecret()+"\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-pairings/browser/approve").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf()).contentType("application/json").content("{\"approvalSecret\":\""+p.approvalSecret()+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.accountName").value("DeviceFixture")).andExpect(jsonPath("$.name").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        assertThrows(ResponseStatusException.class,()->service.browserPairing(auth(),p.approvalSecret(),true,false));
        var token=service.exchange(p.pollingSecret());var view=service.browserPairing(auth(),p.approvalSecret(),false,false);
        assertEquals("PAIRED",view.status());assertEquals(token.deviceId(),view.deviceId());
        assertFalse(service.list(auth()).getFirst().connected());
    }
    @Test void browserApprovalCannotBypassConsentExpiryOrAccountBinding() {
        var p=service.start(DeviceService.CAPTURE_SCOPE);pairingIds.add(db.queryForObject("SELECT id FROM rbgs.device_pairings WHERE polling_hash=?",UUID.class,DeviceService.hash(p.pollingSecret())));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.browserPairing(auth(),p.approvalSecret(),true,false)).getStatusCode().value());
        service.browserPairing(auth(),p.approvalSecret(),true,true);
        String ownSubject=subject;subject=UUID.randomUUID().toString();var other=accounts.upsert(ISSUER,subject,"OtherFixture");
        try{assertEquals(410,assertThrows(ResponseStatusException.class,()->service.browserPairing(auth(),p.approvalSecret(),false,false)).getStatusCode().value());}finally{subject=ownSubject;db.update("DELETE FROM rbgs.accounts WHERE id=?",other.id());}
        db.update("UPDATE rbgs.device_pairings SET expires_at=? WHERE polling_hash=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),DeviceService.hash(p.pollingSecret()));
        assertThrows(ResponseStatusException.class,()->service.browserPairing(auth(),p.approvalSecret(),false,false));
    }
    @Test void heartbeatIsAuthenticatedServerTimedAndExpiresWithoutClaimingTheAppClosed() throws Exception {
        var token=paired();String bearer="Bearer "+token.token();
        service.status(bearer);assertNull(service.list(auth()).getFirst().lastSeenAt());
        mvc.perform(post("/api/v1/companion/heartbeat").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/companion/heartbeat").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(account.id().toString()));
        assertTrue(service.list(auth()).getFirst().connected());
        db.update("UPDATE rbgs.devices SET last_seen_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(46)),token.deviceId());assertFalse(service.list(auth()).getFirst().connected());
        db.update("UPDATE rbgs.devices SET last_seen_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(60)),token.deviceId());assertFalse(service.list(auth()).getFirst().connected());
        service.heartbeat(bearer);service.revoke(auth(),token.deviceId());assertTrue(service.list(auth()).isEmpty());
        mvc.perform(post("/api/v1/companion/heartbeat").header("Authorization",bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/device-pairings/browser/details").header("Authorization",bearer).contentType("application/json").content("{\"approvalSecret\":\""+"a".repeat(43)+"\"}")).andExpect(status().isForbidden());
    }

    @Test void deviceRejectsRevocationExpiryAndAccountSuspensionImmediately() {
        var token = paired();
        db.update("UPDATE rbgs.accounts SET account_status = 'SUSPENDED' WHERE id = ?", account.id());
        assertThrows(ResponseStatusException.class, () -> service.status("Bearer " + token.token()));
        db.update("UPDATE rbgs.accounts SET account_status = 'ACTIVE' WHERE id = ?", account.id());
        assertNotNull(service.status("Bearer " + token.token()));
        db.update("UPDATE rbgs.devices SET expires_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), token.deviceId());
        assertThrows(ResponseStatusException.class, () -> service.status("Bearer " + token.token()));
        var second = paired();
        service.revoke(auth(), second.deviceId());
        service.revoke(auth(), second.deviceId());
        assertThrows(ResponseStatusException.class, () -> service.status("Bearer " + second.token()));
    }

    @Test void webSessionsCannotReplaceDeviceSecretsAndDevicesCannotAccessWeb() throws Exception {
        var token = paired();
        mvc.perform(get("/api/v1/companion/status").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/devices/me").header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isUnauthorized());
        String otherSubject = UUID.randomUUID().toString();
        var other = accounts.upsert(ISSUER, otherSubject, "OtherFixture");
        try {
            mvc.perform(delete("/api/v1/devices/" + token.deviceId()).with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(otherSubject))).with(csrf()))
                    .andExpect(status().isNotFound());
            assertNotNull(service.status("Bearer " + token.token()));
        } finally { db.update("DELETE FROM rbgs.accounts WHERE id = ?", other.id()); }
    }

    @Test void concurrentExchangeCreatesExactlyOneDevice() throws Exception {
        var p = start(); service.approve(auth(), p.code());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> exchange = () -> { gate.await(); try { service.exchange(p.pollingSecret()); return true; } catch (ResponseStatusException e) { return false; } };
            var first = executor.submit(exchange); var second = executor.submit(exchange); gate.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, service.list(auth()).size());
    }

    @Test void suspendedApprovalAndTokenExchangeAreRejected() throws Exception {
        var p = start(); service.approve(auth(), p.code());
        db.update("UPDATE rbgs.accounts SET account_status = 'SUSPENDED' WHERE id = ?", account.id());
        assertThrows(ResponseStatusException.class, () -> service.exchange(p.pollingSecret()));
        mvc.perform(post("/api/v1/device-pairings/approve").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject))).with(csrf())
                .contentType("application/json").content("{\"code\":\"" + p.code() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void publicCreationIsBoundedAndInputsAreValidated() throws Exception {
        String ip = "192.0.2." + (10 + new Random().nextInt(200));
        for (int i = 0; i < 5; i++) {
            var result = mvc.perform(post("/api/v1/device-pairings").with(r -> { r.setRemoteAddr(ip); return r; })
                    .contentType("application/json").content(i==0?"{\"name\":\"Legacy ignored label\"}":"{}"))
                    .andExpect(status().isOk()).andReturn();
            // Remove only this test's public creations, including ones not associated to an account.
            String body = result.getResponse().getContentAsString();
            String secret = body.split("\"pollingSecret\":\"")[1].split("\"")[0];
            pairingIds.add(db.queryForObject("SELECT id FROM rbgs.device_pairings WHERE polling_hash = ?", UUID.class, DeviceService.hash(secret)));
        }
        mvc.perform(post("/api/v1/device-pairings").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/device-pairings/token").contentType("application/json").content("{\"pollingSecret\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void listingAutomaticallyExcludesRevokedAndExpiredDevicesAndKeepsTheirHistory() throws Exception {
        var valid=paired();var revoked=paired();var expired=paired();
        service.revoke(auth(),revoked.deviceId());
        db.update("UPDATE rbgs.devices SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),expired.deviceId());
        // A valid offline companion is still a linked device and remains revocable.
        assertFalse(service.list(auth()).getFirst().connected());
        mvc.perform(get("/api/v1/devices/me").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(valid.deviceId().toString()));
        service.revoke(auth(),valid.deviceId());
        assertTrue(service.list(auth()).isEmpty());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM rbgs.devices WHERE account_id=?",Integer.class,account.id()));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM rbgs.devices WHERE account_id=? AND removed_at IS NULL",Integer.class,account.id()));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.status("Bearer "+expired.token())).getStatusCode().value());
    }

    @Test void revokedDeviceCanBeRemovedFromListingWithoutDeletingItsCredentialHistory() {
        var token=paired();service.revoke(auth(),token.deviceId());
        service.removeInactive(auth(),token.deviceId());
        service.removeInactive(auth(),token.deviceId());
        assertTrue(service.list(auth()).isEmpty());
        assertNotNull(db.queryForObject("SELECT removed_at FROM rbgs.devices WHERE id=?",java.sql.Timestamp.class,token.deviceId()));
        assertEquals(DeviceService.hash(token.token()),db.queryForObject("SELECT token_hash FROM rbgs.devices WHERE id=?",String.class,token.deviceId()));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.status("Bearer "+token.token())).getStatusCode().value());
    }

    @Test void expiredDeviceCanBeRemovedWithoutRevokingFirst() throws Exception {
        var token=paired();
        db.update("UPDATE rbgs.devices SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),token.deviceId());
        mvc.perform(delete("/api/v1/devices/"+token.deviceId()+"/record").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
        assertTrue(service.list(auth()).isEmpty());
        assertNull(db.queryForObject("SELECT revoked_at FROM rbgs.devices WHERE id=?",java.sql.Timestamp.class,token.deviceId()));
    }

    @Test void removalRejectsAnActiveDeviceEvenWhenItHasNoRecentHeartbeat() throws Exception {
        var token=paired();
        assertFalse(service.list(auth()).getFirst().connected());
        mvc.perform(delete("/api/v1/devices/"+token.deviceId()+"/record").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf()))
                .andExpect(status().isConflict());
        service.heartbeat("Bearer "+token.token());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.removeInactive(auth(),token.deviceId())).getStatusCode().value());
        assertEquals(token.deviceId(),service.list(auth()).getFirst().id());
        assertNotNull(service.status("Bearer "+token.token()));
    }

    @Test void removalRequiresWebAuthenticationCsrfAndOwnership() throws Exception {
        var token=paired();service.revoke(auth(),token.deviceId());String path="/api/v1/devices/"+token.deviceId()+"/record";
        mvc.perform(delete(path).with(csrf()).header("Authorization","Bearer "+token.token())).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject)))).andExpect(status().isForbidden());
        String otherSubject=UUID.randomUUID().toString();var other=accounts.upsert(ISSUER,otherSubject,"OtherRemovalFixture");
        try {
            mvc.perform(delete(path).with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(otherSubject))).with(csrf())).andExpect(status().isNotFound());
            assertNull(db.queryForObject("SELECT removed_at FROM rbgs.devices WHERE id=?",java.sql.Timestamp.class,token.deviceId()));
        } finally {db.update("DELETE FROM rbgs.accounts WHERE id=?",other.id());}
        mvc.perform(delete("/api/v1/devices/"+UUID.randomUUID()+"/record").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/devices/me").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test void deviceLimitCanBeRecoveredByRevokingAnOwnedDevice() {
        for (int i = 0; i < 5; i++) paired();
        var sixth = start(); service.approve(auth(), sixth.code());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.exchange(sixth.pollingSecret())).getStatusCode().value());
        service.revoke(auth(), service.list(auth()).getFirst().id());
        assertEquals("PAIRED", service.exchange(sixth.pollingSecret()).status());
        assertEquals(5, service.list(auth()).size());
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM rbgs.devices WHERE account_id=?",Integer.class,account.id()));
    }
}
