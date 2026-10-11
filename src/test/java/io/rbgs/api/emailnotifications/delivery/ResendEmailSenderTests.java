package io.rbgs.api.emailnotifications.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import java.net.http.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ResendEmailSenderTests {
    @Test void acceptsMessagesWithStableIdempotencyAndHandlesFailuresConservatively() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var settings = new EmailNotificationSettings("", "notifications@rbgs.io", "x".repeat(32),
                "http://localhost:5173", false, 90, true, "resend", "test-key");
        var sender = new ResendEmailSender(settings, client, new ObjectMapper());
        var delivery = new EmailOutbox.Delivery(UUID.randomUUID(), "test@example.com", "Soon™", "<p>Confirm</p>", "Confirm", 0, Instant.now());
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"id\":\"12345678-1234-1234-1234-123456789012\"}");
        assertEquals(EmailSendResult.Outcome.ACCEPTED, sender.send(delivery).outcome());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("rbgs/" + delivery.id(), request.getValue().headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals("Bearer test-key", request.getValue().headers().firstValue("Authorization").orElseThrow());
        when(response.statusCode()).thenReturn(429);
        assertEquals(EmailSendResult.Outcome.RETRY, sender.send(delivery).outcome());
        when(response.statusCode()).thenReturn(500);
        assertEquals(EmailSendResult.Outcome.REVIEW, sender.send(delivery).outcome());
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        assertEquals(EmailSendResult.Outcome.REVIEW, sender.send(delivery).outcome());
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new HttpTimeoutException("timeout"));
        assertEquals(EmailSendResult.Outcome.REVIEW, sender.send(delivery).outcome());
    }
}
