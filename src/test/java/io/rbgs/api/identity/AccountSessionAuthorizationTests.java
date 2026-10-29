package io.rbgs.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import org.springframework.session.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SuppressWarnings({"rawtypes", "unchecked"}) // JDBC session type is package-private.
class AccountSessionAuthorizationTests {
    private static final String ISSUER = "https://idp.example";
    private static final String MODERATION = "/api/v1/moderation/season-notifications";

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate database;
    @Autowired SessionRepository sessions;
    private final java.util.ArrayList<String> sessionIds = new java.util.ArrayList<>();

    @AfterEach void deleteSessions() { sessionIds.forEach(sessions::deleteById); }

    @Test
    void suspensionRejectsTheNextRequestFromEveryExistingSession() throws Exception {
        String subject = UUID.randomUUID().toString();
        Account account = accounts.upsert(ISSUER, subject, "Player#1234");
        Cookie first = session(subject, "USER");
        Cookie second = session(subject, "USER");
        try {
            mvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isOk());
            database.update("UPDATE rbgs.accounts SET account_status = 'SUSPENDED' WHERE id = ?", account.id());
            for (Cookie session : List.of(first, second)) {
                mvc.perform(get("/api/v1/characters/me").cookie(session)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.status").value(403));
                mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isForbidden());
                mvc.perform(get("/api/v1/health").cookie(session)).andExpect(status().isOk());
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
        Cookie session = session(subject, "MODERATOR");
        try {
            mvc.perform(get(MODERATION).cookie(session)).andExpect(status().isOk());
            database.update("UPDATE rbgs.accounts SET account_role = 'USER' WHERE id = ?", account.id());
            mvc.perform(get(MODERATION).cookie(session)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("USER"));
            database.update("UPDATE rbgs.accounts SET account_role = 'MODERATOR', account_status = 'SUSPENDED' WHERE id = ?",
                    account.id());
            mvc.perform(get(MODERATION).cookie(session)).andExpect(status().isForbidden());
        } finally {
            database.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
        }
    }

    @Test
    void missingAccountCannotUseAnExistingSession() throws Exception {
        String subject = UUID.randomUUID().toString();
        Account account = accounts.upsert(ISSUER, subject, "Player#1234");
        Cookie session = session(subject, "MODERATOR");
        try {
            mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
            database.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
            mvc.perform(get(MODERATION).cookie(session)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isForbidden());
        } finally {
            database.update("DELETE FROM rbgs.accounts WHERE id = ?", account.id());
        }
    }

    private Cookie session(String subject, String role) {
        OidcIdToken token = OidcIdToken.withTokenValue("fixture-id-token")
                .issuer(ISSUER).subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var user = new DefaultOidcUser(authorities, token);
        var authentication = new OAuth2AuthenticationToken(user, authorities, "battle-net");
        var session = sessions.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        sessions.save(session);
        sessionIds.add(session.getId());
        return new Cookie("JSESSIONID", session.getId());
    }
}
