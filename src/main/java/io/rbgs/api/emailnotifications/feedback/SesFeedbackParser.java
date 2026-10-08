package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class SesFeedbackParser {
    public SesFeedback parse(JsonNode event, String accountId) {
        try {
            JsonNode mail = event.path("mail");
            if (!accountId.equals(mail.path("sendingAccountId").asText())) throw invalid();
            String type = event.path("notificationType").asText(event.path("eventType").asText());
            if (!Set.of("Bounce", "Complaint", "Delivery").contains(type)) return null;
            String provider = mail.path("messageId").asText();
            if (provider.isBlank() || provider.length() > 100) throw invalid();
            JsonNode details = event.path(type.toLowerCase(Locale.ROOT));
            Instant timestamp = Instant.parse(details.path("timestamp").asText());
            String detail = switch (type) {
                case "Bounce" -> details.path("bounceType").asText();
                case "Complaint" -> details.path("complaintFeedbackType").asText("unspecified");
                default -> "delivered";
            };
            if (!detail.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
            Set<String> destinations = new HashSet<>();
            for (JsonNode address : mail.path("destination")) destinations.add(normalize(address.asText()));
            JsonNode addresses = details.path(switch (type) {
                case "Bounce" -> "bouncedRecipients";
                case "Complaint" -> "complainedRecipients";
                default -> "recipients";
            });
            if (!addresses.isArray() || addresses.isEmpty()) throw invalid();
            Set<String> recipients = new TreeSet<>();
            for (JsonNode address : addresses) {
                String normalized = normalize(type.equals("Delivery") ? address.asText() : address.path("emailAddress").asText());
                if (!destinations.contains(normalized)) throw invalid();
                recipients.add(normalized);
            }
            EmailSuppressionReason reason = type.equals("Bounce") && detail.equals("Permanent") ? EmailSuppressionReason.PERMANENT_BOUNCE
                    : type.equals("Complaint") && !detail.equals("not-spam") ? EmailSuppressionReason.COMPLAINT : null;
            return new SesFeedback(type, provider, detail, timestamp, Set.copyOf(recipients), reason);
        } catch (EmailNotificationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private static String normalize(String address) {
        String email = address.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+")) throw invalid();
        return email;
    }
    private static EmailNotificationException invalid() { return new EmailNotificationException(Reason.INVALID_INPUT, "Invalid SES feedback event"); }
}
