package io.rbgs.api.emailnotifications.feedback;

import com.svix.Webhook;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ResendFeedbackVerifier {
    private final String secret;

    public ResendFeedbackVerifier(@Value("${rbgs.notifications.resend.webhook-secret:}") String secret) {
        this.secret = secret;
    }

    public UUID verify(String body, String id, String timestamp, String signature) {
        if (secret.isBlank()) throw new EmailNotificationException(Reason.UNAVAILABLE, "Resend feedback is not configured");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || timestamp == null || signature == null
                || timestamp.length() > 20 || signature.length() > 4096) throw untrusted();
        try {
            var headers = Map.of("svix-id", List.of(id), "svix-timestamp", List.of(timestamp),
                    "svix-signature", List.of(signature));
            new Webhook(secret).verify(body, headers);
            return UUID.nameUUIDFromBytes(("resend:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw untrusted();
        }
    }

    private static EmailNotificationException untrusted() {
        return new EmailNotificationException(Reason.UNTRUSTED, "Invalid Resend webhook signature");
    }
}
