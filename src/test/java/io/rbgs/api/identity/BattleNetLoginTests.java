package io.rbgs.api.identity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.rbgs.api.foundation.SecurityConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import jakarta.servlet.http.Cookie;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@WebMvcTest(AccountController.class)
@Import({SecurityConfiguration.class, BattleNetLoginTests.StubRegistration.class})
@TestPropertySource(properties = "rbgs.auth.web-origin=http://localhost:5173")
class BattleNetLoginTests {
    private static final AtomicReference<String> nonce = new AtomicReference<>();
    private static final AtomicReference<String> battleTag = new AtomicReference<>("Player#1234");
    private static final RSAKey key;
    private static final HttpServer provider;
    private static final String issuer;

    static {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            issuer = "http://127.0.0.1:" + provider.getAddress().getPort();
            provider.createContext("/token", BattleNetLoginTests::token);
            provider.createContext("/jwks", exchange -> send(exchange, 200,
                    "{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}"));
            provider.createContext("/userinfo", exchange -> send(exchange, 200,
                    "{\"sub\":\"stable-subject\",\"battletag\":\"" + battleTag.get() + "\"}"));
            provider.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean AccountRepository accounts;

    @AfterAll
    static void stopProvider() { provider.stop(0); }

    @Test
    void successfulLoginCreatesSessionAndCallbackCannotBeReused() throws Exception {
        UUID id = UUID.randomUUID();
        when(accounts.upsert(eq(issuer), eq("stable-subject"), anyString()))
                .thenReturn(new Account(id, "Player#1234", "EU", "ACTIVE", "USER"));
        when(accounts.findByIdentity(issuer, "stable-subject"))
                .thenReturn(new Account(id, "Player#1234", "EU", "ACTIVE", "USER"));
        Login login = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "valid").param("state", login.state()).session(login.session()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost:5173/"));
        MvcResult profile = mvc.perform(get("/api/v1/auth/me").session(login.session()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "valid").param("state", login.state()).session(login.session()))
                .andExpect(status().is3xxRedirection());
        verify(accounts).upsert(issuer, "stable-subject", "Player#1234");

        Cookie csrf = profile.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/v1/auth/logout").session(login.session()).cookie(csrf))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").session(login.session()).cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").session(login.session()))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelledLoginAndInvalidStateDoNotCreateAccount() throws Exception {
        Login cancelled = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("error", "access_denied").param("state", cancelled.state()).session(cancelled.session()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost:5173/?auth=failed"));
        Login invalid = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "valid").param("state", "wrong-state").session(invalid.session()))
                .andExpect(status().is3xxRedirection());
        verify(accounts, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    void expiredCredentialAndProviderOutageDoNotCreateAccount() throws Exception {
        Login expired = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "expired").param("state", expired.state()).session(expired.session()))
                .andExpect(status().is3xxRedirection());
        Login outage = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "outage").param("state", outage.state()).session(outage.session()))
                .andExpect(status().is3xxRedirection());
        verify(accounts, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    void invalidNonceIssuerAudienceAndExpiredIdTokenAreRejected() throws Exception {
        for (String code : new String[] {"bad-nonce", "bad-issuer", "bad-audience", "expired-id"}) {
            Login login = startLogin();
            mvc.perform(get("/login/oauth2/code/battle-net")
                    .param("code", code).param("state", login.state()).session(login.session()))
                    .andExpect(status().is3xxRedirection());
        }
        verify(accounts, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    void changedBattleTagUsesTheSameProviderIdentity() throws Exception {
        UUID id = UUID.randomUUID();
        when(accounts.upsert(eq(issuer), eq("stable-subject"), anyString()))
                .thenAnswer(call -> new Account(id, call.getArgument(2), "EU", "ACTIVE", "USER"));
        battleTag.set("Player#1234");
        Login first = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "valid").param("state", first.state()).session(first.session()))
                .andExpect(status().is3xxRedirection());
        battleTag.set("Renamed#5678");
        Login second = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "valid").param("state", second.state()).session(second.session()))
                .andExpect(status().is3xxRedirection());
        verify(accounts).upsert(issuer, "stable-subject", "Player#1234");
        verify(accounts).upsert(issuer, "stable-subject", "Renamed#5678");
        battleTag.set("Player#1234");
    }

    @Test
    void suspendedAccountCannotCreateASession() throws Exception {
        when(accounts.upsert(eq(issuer), eq("stable-subject"), anyString()))
                .thenReturn(new Account(UUID.randomUUID(), "Player#1234", "EU", "SUSPENDED", "USER"));
        Login login = startLogin();
        mvc.perform(get("/login/oauth2/code/battle-net")
                .param("code", "valid").param("state", login.state()).session(login.session()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost:5173/?auth=failed"));
        mvc.perform(get("/api/v1/auth/me").session(login.session()))
                .andExpect(status().isNoContent());
    }

    private Login startLogin() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/battle-net"))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI redirect = URI.create(result.getResponse().getRedirectedUrl());
        Map<String, String> query = Arrays.stream(redirect.getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(part -> part[0],
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
        nonce.set(query.get("nonce"));
        return new Login((MockHttpSession) result.getRequest().getSession(false), query.get("state"));
    }

    private static void token(HttpExchange exchange) throws IOException {
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (form.contains("code=expired")) {
            send(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        if (form.contains("code=outage")) {
            send(exchange, 503, "{\"error\":\"server_error\"}");
            return;
        }
        try {
            String tokenIssuer = form.contains("code=bad-issuer") ? issuer + "/wrong" : issuer;
            String audience = form.contains("code=bad-audience") ? "other-client" : "test-client";
            String tokenNonce = form.contains("code=bad-nonce") ? "wrong-nonce" : nonce.get();
            Instant expiry = form.contains("code=expired-id")
                    ? Instant.now().minusSeconds(60) : Instant.now().plusSeconds(300);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                    new JWTClaimsSet.Builder().issuer(tokenIssuer).subject("stable-subject")
                            .audience(audience).issueTime(Date.from(Instant.now().minusSeconds(120)))
                            .expirationTime(Date.from(expiry))
                            .claim("nonce", tokenNonce).build());
            jwt.sign(new RSASSASigner(key));
            send(exchange, 200, "{\"access_token\":\"local-token\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":300,\"scope\":\"openid\",\"id_token\":\"" + jwt.serialize() + "\"}");
        } catch (Exception error) {
            send(exchange, 500, "{\"error\":\"server_error\"}");
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record Login(MockHttpSession session, String state) { }

    @TestConfiguration
    static class StubRegistration {
        @Bean
        ClientRegistrationRepository registrations() {
            ClientRegistration registration = ClientRegistration.withRegistrationId("battle-net")
                    .clientId("test-client").clientSecret("test-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid").authorizationUri(issuer + "/authorize")
                    .tokenUri(issuer + "/token").jwkSetUri(issuer + "/jwks")
                    .userInfoUri(issuer + "/userinfo").userNameAttributeName("sub")
                    .issuerUri(issuer).clientName("Battle.net stub").build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
