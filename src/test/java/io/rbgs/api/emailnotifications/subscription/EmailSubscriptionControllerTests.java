package io.rbgs.api.emailnotifications.subscription;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;

import io.rbgs.api.foundation.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmailSubscriptionController.class)
@Import({SecurityConfiguration.class, EmailRateLimiter.class})
class EmailSubscriptionControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean EmailSubscriptionService service;
    @MockitoBean EmailNotificationSettings settings;
    private static final String JSON = "{\"email\":\"player@example.com\",\"language\":\"es\",\"website\":\"\"}";

    @Test void explicitNotificationRequestRequiresCsrfAndValidEmailAndLanguage() throws Exception {
        mvc.perform(post("/api/v1/season-notifications").contentType("application/json").content(JSON))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/season-notifications").with(csrf()).contentType("application/json").content(JSON))
                .andExpect(status().isAccepted());
        verify(service).subscribe("player@example.com", "es", "");
        for (String invalid : new String[] {
                JSON.replace("player@example.com", "not-an-email"), JSON.replace("\"es\"", "\"fr\"")}) {
            mvc.perform(post("/api/v1/season-notifications").with(csrf()).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
    }

    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    @Test void statusEstablishesCsrfCookieWithoutLeakingConfiguration() throws Exception {
        when(settings.enabled()).thenReturn(true);
        mvc.perform(get("/api/v1/season-notifications")).andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.subscriptionsAvailable").value(true))
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test void disabledEmailHidesTheWidgetEvenDuringPreseason() throws Exception {
        when(settings.enabled()).thenReturn(false);
        mvc.perform(get("/api/v1/season-notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false))
                .andExpect(jsonPath("$.subscriptionsAvailable").value(false));
    }

    @Test void publicLinkActionsRequireCsrfAndLaunchRequiresModerator() throws Exception {
        String token = "a".repeat(43);
        for (String action : new String[] {"confirm", "unsubscribe"}) {
            mvc.perform(post("/api/v1/season-notifications/" + action).contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/season-notifications/" + action).with(csrf()).contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isNoContent());
        }
        String launch = "/api/v1/moderation/season-notifications/launch";
        mvc.perform(post(launch).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(launch).with(csrf()).with(oidcLogin())).andExpect(status().isForbidden());
        when(service.launch()).thenReturn(3);
        mvc.perform(post(launch).with(csrf()).with(oidcLogin().authorities(() -> "ROLE_MODERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(3));
    }

    @Test void repeatedRequestsAreLimited() throws Exception {
        for (int i = 0; i < 20; i++) mvc.perform(post("/api/v1/season-notifications").with(request -> { request.setRemoteAddr("192.0.2.99"); return request; }).with(csrf()).contentType("application/json").content(JSON))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/season-notifications").with(request -> { request.setRemoteAddr("192.0.2.99"); return request; }).with(csrf()).contentType("application/json").content(JSON))
                .andExpect(status().isTooManyRequests());
    }

    @Test void visitorsSharingAProxyCanSubscribeDifferentAddresses() throws Exception {
        for (int i = 0; i < 25; i++) {
            mvc.perform(post("/api/v1/season-notifications")
                    .with(request -> { request.setRemoteAddr("192.0.2.100"); return request; }).with(csrf())
                    .contentType("application/json").content(JSON.replace("player@", "player" + i + "@")))
                    .andExpect(status().isAccepted());
        }
    }
}
