package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailFeedbackSettings;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import software.amazon.awssdk.messagemanager.sns.model.SnsSubscriptionConfirmation;

@Component
public class SnsSubscriptionConfirmer {
    private final EmailFeedbackSettings settings;
    private final HttpClient client;

    public SnsSubscriptionConfirmer(EmailFeedbackSettings settings, HttpClient client) {
        this.settings = settings;
        this.client = client;
    }

    public void confirm(SnsSubscriptionConfirmation message) {
        if (!settings.configured() || !settings.topicArn().equals(message.topicArn()) || message.token() == null || message.token().isBlank())
            throw new EmailNotificationException(Reason.UNTRUSTED, "Untrusted SNS subscription");
        URI endpoint = URI.create("https://sns." + settings.region() + ".amazonaws.com/?Action=ConfirmSubscription&TopicArn="
                + encode(settings.topicArn()) + "&Token=" + encode(message.token()));
        try {
            var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) throw new EmailNotificationException(Reason.UNAVAILABLE, "SNS subscription confirmation failed");
        } catch (java.io.IOException error) {
            throw new EmailNotificationException(Reason.UNAVAILABLE, "SNS subscription confirmation failed");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new EmailNotificationException(Reason.UNAVAILABLE, "SNS subscription confirmation interrupted");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
