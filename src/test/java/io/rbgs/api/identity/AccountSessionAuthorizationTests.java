package io.rbgs.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountSessionAuthorizationTests {
    private static final String ISSUER = "https://idp.example";
    private static final String MODERATION = "/api/v1/moderation/season-notifications";

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate database;

    @Test
    void suspensionRejectsTheNextRequestFromEveryExistingSession() throws Exception {
        String subject = UUID.randomUUID().toString();
        Account account = accounts.upsert(ISSUER, subject, "Player#1234");
        MockHttpSession first = session(subject, "USER");
        MockHttpSession second = session(subject, "USER");
        try {
            mvc.perform(get("/api/v1/auth/me").session(first)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/auth/me").session(second)).andExpect(status().isOk());
            database.update("UPDATE rbgs.accounts SET account_status = 'SUSPENDED' WHERE id = ?", account.id());
            for (MockHttpSession session : List.of(first, second)) {
                mvc.perform(get("/api/v1/characters/me").session(session)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.status").value(403));
                mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isForbidden());
                mvc.perform(get("/api/v1/health").session(session)).andExpect(status().isOk());
            }
        } finally {
            database.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
        }
    }

    @Test
    void demotionRejectsStaleModeratorAuthoritiesButPreservesRegularAccess() throws Exception {
        String subject = UUID.randomUUID().toString();
        Account account = accounts.upsert(ISSUER, subject, "Player#1234");
        database.update("UPDATE rbgs.accounts SET account_role = 'MODERATOR' WHERE id = ?", account.id());
        MockHttpSession session = session(subject, "MODERATOR");
        try {
            mvc.perform(get(MODERATION).session(session)).andExpect(status().isOk());
            database.update("UPDATE rbgs.accounts SET account_role = 'USER' WHERE id = ?", account.id());
            mvc.perform(get(MODERATION).session(session)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("USER"));
            database.update("UPDATE rbgs.accounts SET account_role = 'MODERATOR', account_status = 'SUSPENDED' WHERE id = ?",
                    account.id());
            mvc.perform(get(MODERATION).session(session)).andExpect(status().isForbidden());
        } finally {
            database.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
        }
    }

    @Test
    void missingAccountCannotUseAnExistingSession() throws Exception {
        String subject = UUID.randomUUID().toString();
        Account account = accounts.upsert(ISSUER, subject, "Player#1234");
        MockHttpSession session = session(subject, "MODERATOR");
        try {
            mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
            database.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
            mvc.perform(get(MODERATION).session(session)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isForbidden());
        } finally {
            database.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
        }
    }

    private static MockHttpSession session(String subject, String role) {
        OidcIdToken token = OidcIdToken.withTokenValue("fixture-id-token")
                .issuer(ISSUER).subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var user = new DefaultOidcUser(authorities, token);
        var authentication = new OAuth2AuthenticationToken(user, authorities, "battle-net");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        return session;
    }
}
