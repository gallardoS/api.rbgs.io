package io.rbgs.api.foundation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.servlet.http.Cookie;
import io.rbgs.api.identity.Account;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.identity.dto.Profile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "server.servlet.session.cookie.secure=true"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PersistentWebSessionTests.Registration.class)
@SuppressWarnings({"rawtypes", "unchecked"}) // JDBC's concrete session type is package-private.
class PersistentWebSessionTests {
    @Autowired MockMvc mvc;
    @Autowired SessionRepository sessions;
    @Autowired OAuth2AuthorizedClientRepository clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AccountService accounts;

    @TestConfiguration
    static class Registration {
        @Bean ClientRegistrationRepository registrations() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("battle-net")
                    .clientId("test").clientSecret("test")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/login/oauth2/code/battle-net")
                    .scope("openid", "wow.profile")
                    .authorizationUri("https://example.test/authorize")
                    .tokenUri("https://example.test/token")
                    .jwkSetUri("https://example.test/keys")
                    .userInfoUri("https://example.test/userinfo").userNameAttributeName("sub").build());
        }
    }

    @Test
    void authenticationAndProviderTokenSurviveRepositoryRecreationAndLogoutDeletesThem() throws Exception {
        var now = Instant.now();
        var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                OidcIdToken.withTokenValue("synthetic-id-token").issuer("https://example.test")
                        .subject("persistent-user").issuedAt(now).expiresAt(now.plusSeconds(3600)).build());
        var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), "battle-net");
        var providerClient = new OAuth2AuthorizedClient(registrations.findByRegistrationId("battle-net"),
                user.getName(), new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        "synthetic-provider-token", now, now.plusSeconds(3600)));
        var request = new MockHttpServletRequest();
        clients.saveAuthorizedClient(providerClient, authentication, request, new MockHttpServletResponse());
        var session = sessions.createSession();
        assertEquals(Duration.ofDays(30), session.getMaxInactiveInterval());
        var attributes = request.getSession().getAttributeNames();
        while (attributes.hasMoreElements()) {
            var name = attributes.nextElement();
            session.setAttribute(name, request.getSession().getAttribute(name));
        }
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        sessions.save(session);
        try {
            // A new repository instance has no in-memory state from the saved login.
            var fresh = new JdbcIndexedSessionRepository(new JdbcTemplate(dataSource), new TransactionTemplate(transactions));
            fresh.setTableName("rbgs.spring_session");
            Session loaded = fresh.findById(session.getId());
            assertNotNull(loaded);
            var restoredRequest = new MockHttpServletRequest();
            loaded.getAttributeNames().forEach(name -> restoredRequest.getSession().setAttribute(name, loaded.getAttribute(name)));
            OAuth2AuthorizedClient restored = clients.loadAuthorizedClient("battle-net", authentication, restoredRequest);
            assertEquals("synthetic-provider-token", restored.getAccessToken().getTokenValue());

            var id = UUID.randomUUID();
            when(accounts.findByIdentity("https://example.test", "persistent-user"))
                    .thenReturn(new Account(id, "Player#1234", "EU", "ACTIVE", "USER"));
            when(accounts.currentUser(any())).thenAnswer(invocation -> {
                var restoredAuthentication = assertInstanceOf(OAuth2AuthenticationToken.class, invocation.getArgument(0));
                assertEquals("persistent-user", restoredAuthentication.getName());
                return new Profile(id, "Player#1234", "EU", "USER");
            });
            mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("JSESSIONID", session.getId())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
            mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("JSESSIONID", session.getId())).with(csrf()))
                    .andExpect(status().isNoContent());
            assertNull(fresh.findById(session.getId()));
        } finally {
            sessions.deleteById(session.getId());
        }
    }

    @Test
    void loginSetsPersistentHttpOnlyCookie() throws Exception {
        var response = mvc.perform(get("/oauth2/authorization/battle-net")).andExpect(status().isFound()).andReturn().getResponse();
        var cookie = response.getCookie("JSESSIONID");
        assertNotNull(cookie);
        assertEquals(Duration.ofDays(30).toSeconds(), cookie.getMaxAge());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        sessions.deleteById(cookie.getValue());
    }

    @Test
    void expiredSessionCannotBeRestored() {
        var session = sessions.createSession();
        session.setMaxInactiveInterval(Duration.ofSeconds(1));
        session.setLastAccessedTime(Instant.now().minusSeconds(60));
        sessions.save(session);
        assertNull(sessions.findById(session.getId()));
    }
}
