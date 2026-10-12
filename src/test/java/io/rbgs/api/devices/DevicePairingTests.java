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
        var p = service.start("Fixture PC");
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(account.id().toString()));
        mvc.perform(get("/api/v1/devices/me").with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].token").doesNotExist())
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
                    .contentType("application/json").content("{\"name\":\"RateFixture\"}"))
                    .andExpect(status().isOk()).andReturn();
            // Remove only this test's public creations, including ones not associated to an account.
            String body = result.getResponse().getContentAsString();
            String secret = body.split("\"pollingSecret\":\"")[1].split("\"")[0];
            pairingIds.add(db.queryForObject("SELECT id FROM rbgs.device_pairings WHERE polling_hash = ?", UUID.class, DeviceService.hash(secret)));
        }
        mvc.perform(post("/api/v1/device-pairings").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType("application/json").content("{\"name\":\"RateFixture\"}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/device-pairings/token").contentType("application/json").content("{\"pollingSecret\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void deviceLimitCanBeRecoveredByRevokingAnOwnedDevice() {
        for (int i = 0; i < 5; i++) paired();
        var sixth = start(); service.approve(auth(), sixth.code());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.exchange(sixth.pollingSecret())).getStatusCode().value());
        service.revoke(auth(), service.list(auth()).getFirst().id());
        assertEquals("PAIRED", service.exchange(sixth.pollingSecret()).status());
        assertEquals(6, service.list(auth()).size());
    }
}
