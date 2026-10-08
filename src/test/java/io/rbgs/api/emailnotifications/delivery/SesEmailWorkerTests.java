package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.config.SesEmailConfiguration;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SesEmailWorkerTests {
    @Test void localCredentialsRequireBothFieldsAndPreserveSessionToken() {
        var configuration = new EmailNotificationSettings("eu-west-1", "notice@example.com", "a".repeat(32), "http://localhost:5173", false, 90, false);
        var configurationFactory = new SesEmailConfiguration(configuration);
        assertThrows(IllegalArgumentException.class, () -> configurationFactory.sesEmailClient("test-access", "", ""));
        assertThrows(IllegalArgumentException.class, () -> configurationFactory.sesEmailClient("", "test-secret", ""));
        try (var client = configurationFactory.sesEmailClient("test-access", "test-secret", "test-session")) {
            var provider = (software.amazon.awssdk.auth.credentials.AwsCredentialsProvider) client.serviceClientConfiguration().credentialsProvider();
            var credentials = provider.resolveCredentials();
            assertEquals("test-access", credentials.accessKeyId());
            assertEquals("test-secret", credentials.secretAccessKey());
            assertEquals("test-session", ((software.amazon.awssdk.auth.credentials.AwsSessionCredentials) credentials).sessionToken());
        }
    }

    @Test void sendsLocalizedUtf8PayloadAndStoresSesReceipt() {
        var settings = settings();
        var outbox = mock(EmailOutbox.class);
        var client = mock(SesV2Client.class);
        var delivery = delivery();
        when(outbox.claim()).thenReturn(delivery);
        when(outbox.canSend(delivery)).thenReturn(true);
        when(client.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("ses-id").build());
        var worker = new SesEmailWorker(settings, outbox, new SesEmailSender(settings, client), mock(EmailRetentionService.class));
        worker.dispatch();
        var request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(request.capture());
        assertEquals("player@example.com", request.getValue().destination().toAddresses().getFirst());
        assertEquals("UTF-8", request.getValue().content().simple().subject().charset());
        assertEquals(delivery.html(), request.getValue().content().simple().body().html().data());
        assertEquals(delivery.id().toString(), request.getValue().emailTags().getFirst().value());
        verify(outbox).sent(delivery, "ses-id");
    }

    @Test void throttlingCanRetryButAmbiguousFailureRequiresReview() {
        var outbox = mock(EmailOutbox.class);
        var client = mock(SesV2Client.class);
        var delivery = delivery();
        when(outbox.claim()).thenReturn(delivery);
        when(outbox.canSend(delivery)).thenReturn(true);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(TooManyRequestsException.builder().message("throttled").build())
                .thenThrow(new RuntimeException("response lost"));
        var worker = new SesEmailWorker(settings(), outbox, new SesEmailSender(settings(), client), mock(EmailRetentionService.class));
        worker.dispatch();
        verify(outbox).failed(delivery, false);
        worker.dispatch();
        verify(outbox).failed(delivery, true);
        verify(outbox, never()).sent(any(), any());
    }

    @Test void suppressionAfterClaimPreventsSending() {
        var outbox = mock(EmailOutbox.class);
        var client = mock(SesV2Client.class);
        when(outbox.claim()).thenReturn(delivery());
        new SesEmailWorker(settings(), outbox, new SesEmailSender(settings(), client), mock(EmailRetentionService.class)).dispatch();
        verifyNoInteractions(client);
    }

    @Test void explicitEnablementAndRegionAreRequired() {
        var disabled = new EmailNotificationSettings("eu-west-1", "notice@example.com", "a".repeat(32), "https://rbgs.io", false, 90, false);
        assertFalse(disabled.enabled());
        var enabled = new EmailNotificationSettings("eu-west-1", "notice@example.com", "a".repeat(32), "https://rbgs.io", false, 90, true);
        assertTrue(enabled.enabled());
        var outbox = mock(EmailOutbox.class);
        new SesEmailWorker(disabled, outbox, mock(EmailSender.class), mock(EmailRetentionService.class)).dispatch();
        verifyNoInteractions(outbox);
    }

    @Test void persistenceFailureAfterAcceptanceDoesNotBecomeSendFailureOrRetry() {
        var outbox = mock(EmailOutbox.class);
        var sender = mock(EmailSender.class);
        var delivery = delivery();
        when(outbox.claim()).thenReturn(delivery);
        when(outbox.canSend(delivery)).thenReturn(true);
        when(sender.send(delivery)).thenReturn(new EmailSendResult(EmailSendResult.Outcome.ACCEPTED, "ses-id"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(outbox).sent(delivery, "ses-id");
        var worker = new SesEmailWorker(settings(), outbox, sender, mock(EmailRetentionService.class));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, worker::dispatch);
        verify(sender, times(1)).send(delivery);
        verify(outbox, never()).failed(any(), anyBoolean());
    }

    @Test void missingAcceptanceReceiptRequiresReview() {
        var client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().build());
        assertEquals(EmailSendResult.Outcome.REVIEW, new SesEmailSender(settings(), client).send(delivery()).outcome());
    }

    private EmailNotificationSettings settings() {
        var settings = mock(EmailNotificationSettings.class);
        when(settings.enabled()).thenReturn(true);
        when(settings.from()).thenReturn("rbgs.io <notice@example.com>");
        return settings;
    }
    private EmailOutbox.Delivery delivery() {
        return new EmailOutbox.Delivery(UUID.randomUUID(), "player@example.com", "Confirma tu dirección", "<p>Confirmar</p>", "Confirmar", 1, Instant.now());
    }
}
