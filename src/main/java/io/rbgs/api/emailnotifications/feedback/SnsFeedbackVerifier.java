package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailFeedbackSettings;
import java.net.URI;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager;
import software.amazon.awssdk.messagemanager.sns.model.SnsMessage;
import tools.jackson.databind.ObjectMapper;

@Component
public class SnsFeedbackVerifier {
    private final Clock clock;
    private final EmailFeedbackSettings settings;
    private final ObjectMapper json;
    private final SnsMessageManager manager;

    public SnsFeedbackVerifier(EmailFeedbackSettings settings, ObjectMapper json, SnsMessageManager manager, Clock clock) {
        this.clock = clock;
        this.settings = settings;
        this.json = json;
        this.manager = manager;
    }

    public SnsMessage verify(String body) {
        Instant now = clock.instant();
        if (!settings.configured()) throw new EmailNotificationException(Reason.UNAVAILABLE, "Email feedback is not configured");
        try {
            var envelope = json.readTree(body);
            if (!settings.topicArn().equals(envelope.path("TopicArn").asText())) throw forbidden();
            URI certificate = URI.create(envelope.path("SigningCertURL").asText());
            if (!"https".equals(certificate.getScheme()) || !certificateHost().equals(certificate.getHost())
                    || certificate.getPort() != -1 || certificate.getUserInfo() != null
                    || certificate.getRawQuery() != null || certificate.getFragment() != null
                    || !certificate.getRawPath().matches("/SimpleNotificationService-[A-Za-z0-9]+\\.pem")) throw forbidden();
            UUID.fromString(envelope.path("MessageId").asText());
            Instant timestamp = Instant.parse(envelope.path("Timestamp").asText());
            if (timestamp.isAfter(now.plus(Duration.ofMinutes(5)))
                    || timestamp.isBefore(now.minus(Duration.ofDays(30)))) throw forbidden();
        } catch (EmailNotificationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new EmailNotificationException(Reason.INVALID_INPUT, "Invalid SNS envelope");
        }
        try {
            return manager.parseMessage(body);
        } catch (SdkClientException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.io.IOException && !(cause instanceof javax.net.ssl.SSLException)) throw new EmailNotificationException(Reason.UNAVAILABLE, "SNS certificate temporarily unavailable");
            }
            throw forbidden();
        }
    }

    private String certificateHost() { return "sns." + settings.region() + ".amazonaws.com"; }
    private EmailNotificationException forbidden() { return new EmailNotificationException(Reason.UNTRUSTED, "Untrusted SNS message"); }
}
