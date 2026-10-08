package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailFeedbackSettings;
import io.rbgs.api.foundation.SecurityConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import software.amazon.awssdk.messagemanager.sns.model.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmailFeedbackController.class)
@Import({SecurityConfiguration.class, EmailFeedbackProcessor.class, SesFeedbackParser.class})
class EmailFeedbackControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean SnsFeedbackVerifier verifier;
    @MockitoBean SnsSubscriptionConfirmer subscriptions;
    @MockitoBean EmailFeedbackService service;
    @MockitoBean EmailFeedbackSettings settings;

    @Test void signedNotificationWorksWithoutSessionOrCsrfButOtherMutationsStillRequireCsrf() throws Exception {
        when(settings.accountId()).thenReturn("123456789012");
        String id = UUID.randomUUID().toString();
        when(verifier.verify("signed-envelope")).thenReturn(SnsNotification.builder().messageId(id).timestamp(Instant.now()).message("""
                {"notificationType":"Bounce","mail":{"sendingAccountId":"123456789012","messageId":"ses-id","destination":["one@example.com"]},
                "bounce":{"bounceType":"Permanent","timestamp":"2026-10-08T12:00:00Z","bouncedRecipients":[{"emailAddress":"one@example.com"}]}}
                """).build());
        mvc.perform(post(EmailFeedbackController.PATH).contentType(MediaType.TEXT_PLAIN).content("signed-envelope")).andExpect(status().isNoContent());
        verify(service).accept(eq(UUID.fromString(id)), any());
        mvc.perform(post("/api/v1/season-notifications").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(EmailFeedbackController.PATH)).andExpect(status().isUnauthorized());
    }

    @Test void invalidSignatureDoesNotReachProcessingOrSubscriptionConfirmation() throws Exception {
        when(verifier.verify("forged")).thenThrow(new EmailNotificationException(Reason.UNTRUSTED, "Untrusted SNS message"));
        mvc.perform(post(EmailFeedbackController.PATH).contentType(MediaType.APPLICATION_JSON).content("forged")).andExpect(status().isForbidden());
        verifyNoInteractions(service, subscriptions);
    }

    @Test void confirmsOnlyVerifiedSubscriptionMessages() throws Exception {
        var confirmation = SnsSubscriptionConfirmation.builder().messageId(UUID.randomUUID().toString()).token("synthetic-token").build();
        when(verifier.verify("signed-confirmation")).thenReturn(confirmation);
        mvc.perform(post(EmailFeedbackController.PATH).contentType(MediaType.TEXT_PLAIN).content("signed-confirmation")).andExpect(status().isNoContent());
        verify(subscriptions).confirm(confirmation);
        verifyNoInteractions(service);
    }

    @Test void oversizedPayloadIsRejectedBeforeValidation() throws Exception {
        mvc.perform(post(EmailFeedbackController.PATH).contentType(MediaType.TEXT_PLAIN).content("x".repeat(256 * 1024 + 1))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(verifier, service, subscriptions);
    }
    @Test void applicationErrorsKeepTheirHttpStatusAndPublicDetail() throws Exception {
        when(verifier.verify("not-configured")).thenThrow(new EmailNotificationException(Reason.UNAVAILABLE, "Email feedback is not configured"));
        mvc.perform(post(EmailFeedbackController.PATH).contentType(MediaType.TEXT_PLAIN).content("not-configured"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Email feedback is not configured"));
        when(verifier.verify("invalid-payload")).thenReturn(SnsNotification.builder().messageId(UUID.randomUUID().toString()).message("not-json").build());
        mvc.perform(post(EmailFeedbackController.PATH).contentType(MediaType.TEXT_PLAIN).content("invalid-payload"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, subscriptions);
    }
}
