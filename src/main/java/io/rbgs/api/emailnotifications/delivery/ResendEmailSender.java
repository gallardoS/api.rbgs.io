package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "rbgs.notifications.provider", havingValue = "resend")
public class ResendEmailSender implements EmailSender {
    private static final Logger LOG = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");
    private final EmailNotificationSettings settings;
    private final HttpClient client;
    private final ObjectMapper json;

    public ResendEmailSender(EmailNotificationSettings settings,
            @Qualifier("resendHttpClient") HttpClient client, ObjectMapper json) {
        this.settings = settings;
        this.client = client;
        this.json = json;
    }

    @Override
    public EmailSendResult send(EmailOutbox.Delivery delivery) {
        if (settings.resendApiKey().isBlank()) return review(delivery, "missing-credentials");
        try {
            var body = Map.of("from", settings.from(), "to", List.of(delivery.email()),
                    "subject", delivery.subject(), "html", delivery.html(), "text", delivery.text(),
                    "tags", List.of(Map.of("name", "outbox_id", "value", delivery.id().toString())));
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + settings.resendApiKey())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "rbgs/" + delivery.id())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 429) return new EmailSendResult(EmailSendResult.Outcome.RETRY, null);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String id = json.readTree(response.body()).path("id").asText();
                if (!id.isBlank() && id.length() <= 100) return new EmailSendResult(EmailSendResult.Outcome.ACCEPTED, id);
            }
            return review(delivery, "http-" + response.statusCode());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return review(delivery, "interrupted");
        } catch (Exception error) {
            return review(delivery, error.getClass().getSimpleName());
        }
    }

    private EmailSendResult review(EmailOutbox.Delivery delivery, String reason) {
        LOG.warn("Email notification {} requires review (provider=resend; reason={})", delivery.id(), reason);
        return new EmailSendResult(EmailSendResult.Outcome.REVIEW, null);
    }
}
