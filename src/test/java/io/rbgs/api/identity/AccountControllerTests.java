package io.rbgs.api.identity;

import java.util.UUID;

import io.rbgs.api.foundation.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfiguration.class, AccountControllerTests.ModeratorProbe.class})
class AccountControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean AccountService accounts;

    @Test
    void anonymousUserHasNoProfile() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isNoContent());
    }

    @Test
    void profileOnlyContainsPublicAccountFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(accounts.currentUser(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new io.rbgs.api.identity.dto.Profile(id, "Player#1234", "EU", "USER"));
        mvc.perform(get("/api/v1/auth/me").with(oidcLogin().idToken(token -> token
                .issuer("https://idp.example").subject("stable-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.displayName").value("Player#1234"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void moderatorRouteRejectsAnonymousAndRegularUsers() throws Exception {
        mvc.perform(get("/api/v1/moderation/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/moderation/probe").with(oidcLogin()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/moderation/probe").with(oidcLogin()
                .authorities(() -> "ROLE_MODERATOR"))).andExpect(status().isOk());
    }

    @RestController
    static class ModeratorProbe {
        @GetMapping("/api/v1/moderation/probe")
        ResponseEntity<Void> probe() { return ResponseEntity.ok().build(); }
    }
}
