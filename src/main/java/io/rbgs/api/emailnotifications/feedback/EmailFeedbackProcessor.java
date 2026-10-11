package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailFeedbackSettings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import software.amazon.awssdk.messagemanager.sns.model.SnsSubscriptionConfirmation;
import tools.jackson.databind.ObjectMapper;

@Service
public class EmailFeedbackProcessor {
    private final SnsFeedbackVerifier verifier;
    private final SnsSubscriptionConfirmer subscriptions;
    private final EmailFeedbackService service;
    private final EmailFeedbackSettings settings;
    private final ObjectMapper json;
    private final SesFeedbackParser parser;

    public EmailFeedbackProcessor(SnsFeedbackVerifier verifier, SnsSubscriptionConfirmer subscriptions,
            EmailFeedbackService service, EmailFeedbackSettings settings, ObjectMapper json, SesFeedbackParser parser) {
        this.verifier = verifier;
        this.subscriptions = subscriptions;
        this.service = service;
        this.settings = settings;
        this.json = json;
        this.parser = parser;
    }

    public void process(String body) {
        var message = verifier.verify(body);
        switch (message.type()) {
            case SUBSCRIPTION_CONFIRMATION -> subscriptions.confirm((SnsSubscriptionConfirmation) message);
            case NOTIFICATION -> {
                EmailFeedback feedback;
                try { feedback = parser.parse(json.readTree(message.message()), settings.accountId()); }
                catch (EmailNotificationException error) { throw error; }
                catch (RuntimeException error) { throw new EmailNotificationException(Reason.INVALID_INPUT, "Invalid SES feedback event"); }
                if (feedback != null) service.accept(UUID.fromString(message.messageId()), feedback);
            }
            case UNSUBSCRIBE_CONFIRMATION -> throw new EmailNotificationException(Reason.CONFLICT, "SNS feedback subscription was removed");
            default -> throw new EmailNotificationException(Reason.INVALID_INPUT, "Unsupported SNS message");
        }
    }
}
