package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResendFeedbackProcessor {
    private final ResendFeedbackVerifier verifier;
    private final ResendFeedbackParser parser;
    private final EmailFeedbackService service;
    private final ObjectMapper json;

    public ResendFeedbackProcessor(ResendFeedbackVerifier verifier, ResendFeedbackParser parser,
            EmailFeedbackService service, ObjectMapper json) {
        this.verifier = verifier;
        this.parser = parser;
        this.service = service;
        this.json = json;
    }

    public void process(String body, String id, String timestamp, String signature) {
        var messageId = verifier.verify(body, id, timestamp, signature);
        EmailFeedback feedback;
        try { feedback = parser.parse(json.readTree(body)); }
        catch (EmailNotificationException error) { throw error; }
        catch (RuntimeException error) { throw new EmailNotificationException(Reason.INVALID_INPUT, "Invalid Resend feedback event"); }
        if (feedback != null) service.accept(messageId, feedback);
    }
}
