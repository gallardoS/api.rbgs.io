package io.rbgs.api.identity;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ApiClientLoginController.class, AccountController.class})
@ActiveProfiles("local")
@Import({io.rbgs.api.foundation.SecurityConfiguration.class, ApiClientLoginSecurityConfiguration.class, ApiClientLoginService.class})
class ApiClientLoginTests {
    private static final java.util.concurrent.atomic.AtomicInteger peers = new java.util.concurrent.atomic.AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AccountService accounts;
    @MockitoBean OAuth2AuthorizedClientRepository clients;
    OAuth2AuthorizedClient client;
    final UUID accountId = UUID.randomUUID();
    @BeforeEach void setup() {
        when(accounts.findByIdentity(anyString(), anyString()))
            .thenReturn(new Account(accountId, "Player#1234", "EU", "ACTIVE", "USER"));
        when(accounts.currentUser(any())).thenReturn(new io.rbgs.api.identity.dto.Profile(accountId,"Player#1234","EU","USER"));
        var registration = ClientRegistration.withRegistrationId("battle-net").clientId("test")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://localhost/callback")
            .authorizationUri("https://example.test/authorize").tokenUri("https://example.test/token").build();
        client = new OAuth2AuthorizedClient(registration, "user", new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "server-only-bnet-token", Instant.now(), Instant.now().plusSeconds(3600)));
        when(clients.loadAuthorizedClient(eq("battle-net"), any(), any())).thenReturn(client);
    }
    String[] start() throws Exception {
        var r = mvc.perform(post("/api/v1/auth/api-client/requests").with(request -> {
            request.setRemoteAddr("127.0.0." + peers.incrementAndGet()); return request;
        })).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andReturn();
        var body = json.readTree(r.getResponse().getContentAsString());
        assertTrue(body.get("approvalPath").asString().startsWith("/api/v1/auth/api-client#"));
        return new String[]{body.get("approvalPath").asString().split("#")[1], body.get("pollingSecret").asString()};
    }
    String body(String secret) { return "{\"secret\":\"" + secret + "\"}"; }
    MockHttpSession approve(String secret) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/v1/auth/api-client/approve").with(oidcLogin().idToken(t -> t.issuer("https://idp.example"))).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "test-csrf")).header("X-XSRF-TOKEN", "test-csrf")
            .contentType(MediaType.APPLICATION_JSON).content(body(secret))).andExpect(status().isNoContent())
            .andReturn().getRequest().getSession(false);
    }
    @Test void handoffCreatesIndependentSessionWithCsrfAndAuthorizedClientAndIsSingleUse() throws Exception {
        var secrets = start(); var browserSession = approve(secrets[0]);
        var result = mvc.perform(post("/api/v1/auth/api-client/token").contentType(MediaType.APPLICATION_JSON)
            .content(body(secrets[1]))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAIRED"))
            .andExpect(jsonPath("$.sessionId").isString()).andExpect(jsonPath("$.csrfToken").isString())
            .andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("server-only-bnet-token"));
        var session = (MockHttpSession)result.getRequest().getSession(false);
        assertNotNull(session);
        assertNotEquals(browserSession.getId(), session.getId());
        assertEquals(session.getId(),json.readTree(result.getResponse().getContentAsString()).get("sessionId").asString());
        String token = json.readTree(result.getResponse().getContentAsString()).get("csrfToken").asString();
        mvc.perform(post("/api/v1/auth/api-client/approve").session(session)
            .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
            .contentType(MediaType.APPLICATION_JSON).content(body(secrets[0]))).andExpect(status().isGone());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(accountId.toString()));
        verify(clients).saveAuthorizedClient(eq(client), any(), any(), any());
        mvc.perform(post("/api/v1/auth/api-client/token").contentType(MediaType.APPLICATION_JSON)
            .content(body(secrets[1]))).andExpect(status().isGone());
        mvc.perform(post("/api/v1/auth/api-client/approve").with(oidcLogin().idToken(t -> t.issuer("https://idp.example"))).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "test-csrf")).header("X-XSRF-TOKEN", "test-csrf")
            .contentType(MediaType.APPLICATION_JSON).content(body(secrets[0]))).andExpect(status().isGone());
    }
    @Test void pendingDoesNotCreateSessionAndApprovalHandleCannotPoll() throws Exception {
        var secrets = start();
        var result=mvc.perform(post("/api/v1/auth/api-client/token").contentType(MediaType.APPLICATION_JSON)
            .content(body(secrets[1]))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        assertNull(result.getRequest().getSession(false));
        mvc.perform(post("/api/v1/auth/api-client/token").contentType(MediaType.APPLICATION_JSON)
            .content(body(secrets[0]))).andExpect(status().isGone());
    }
    @Test void approvalRequiresWebAuthenticationAndCsrf() throws Exception {
        var secrets=start();
        mvc.perform(post("/api/v1/auth/api-client/approve").cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "test-csrf")).header("X-XSRF-TOKEN", "test-csrf").contentType(MediaType.APPLICATION_JSON)
            .content(body(secrets[0]))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/api-client/approve").with(oidcLogin().idToken(t -> t.issuer("https://idp.example"))).contentType(MediaType.APPLICATION_JSON)
            .content(body(secrets[0]))).andExpect(status().isForbidden());
    }
    @Test void nativeTransportRejectsBrowserRequestsAndExistingSessions() throws Exception {
        mvc.perform(post("/api/v1/auth/api-client/requests").header("Origin","https://evil.example"))
            .andExpect(status().isForbidden());
        var secrets=start(); approve(secrets[0]);
        mvc.perform(post("/api/v1/auth/api-client/token").header("Sec-Fetch-Site","same-origin")
            .contentType(MediaType.APPLICATION_JSON).content(body(secrets[1]))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/api-client/token").session(new MockHttpSession())
            .contentType(MediaType.APPLICATION_JSON).content(body(secrets[1]))).andExpect(status().isConflict());
    }
    @Test void suspensionBetweenApprovalAndRedemptionIsEnforced() throws Exception {
        var secrets=start(); approve(secrets[0]);
        when(accounts.findByIdentity(anyString(),anyString())).thenReturn(new Account(accountId,"Player#1234","EU","SUSPENDED","USER"));
        mvc.perform(post("/api/v1/auth/api-client/token").contentType(MediaType.APPLICATION_JSON)
            .content(body(secrets[1]))).andExpect(status().isForbidden());
    }
    @Test void missingBnetClientCannotBeApproved() throws Exception {
        var secrets=start(); when(clients.loadAuthorizedClient(eq("battle-net"),any(),any())).thenReturn(null);
        mvc.perform(post("/api/v1/auth/api-client/approve").with(oidcLogin().idToken(t -> t.issuer("https://idp.example"))).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "test-csrf")).header("X-XSRF-TOKEN", "test-csrf")
            .contentType(MediaType.APPLICATION_JSON).content(body(secrets[0]))).andExpect(status().isUnauthorized());
    }
    @Test void browserPageIsPublicUncachedAndLoginHasFixedTarget() throws Exception {
        var page = mvc.perform(get("/api/v1/auth/api-client")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
            .andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("Vincular cliente de API"));
        assertTrue(page.contains("/api/v1/auth/api-client/approve"));
        assertFalse(page.contains("Bruno"));
        var result=mvc.perform(get("/api/v1/auth/api-client/login")).andExpect(status().isFound())
            .andExpect(header().string("Location","/oauth2/authorization/battle-net")).andReturn();
        assertEquals(true,result.getRequest().getSession().getAttribute(ApiClientLoginController.RETURN_TO_API_CLIENT));
    }
}
