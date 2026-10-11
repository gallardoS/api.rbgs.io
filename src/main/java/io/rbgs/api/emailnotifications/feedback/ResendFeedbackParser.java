package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ResendFeedbackParser {
    private final EmailNotificationSettings settings;
    private final Clock clock;

    public ResendFeedbackParser(EmailNotificationSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public EmailFeedback parse(JsonNode event) {
        try {
            String type = event.path("type").asText();
            if (!Set.of("email.bounced", "email.complained", "email.delivered").contains(type)) return null;
            JsonNode data = event.path("data");
            String expectedFrom = mailbox(settings.from());
            if (expectedFrom.isBlank() || !expectedFrom.equals(mailbox(data.path("from").asText()))) throw invalid();
            String providerId = UUID.fromString(data.path("email_id").asText()).toString();
            Instant occurredAt = Instant.parse(event.path("created_at").asText());
            if (occurredAt.isAfter(clock.instant().plusSeconds(300))) throw invalid();
            JsonNode addresses = data.path("to");
            if (!addresses.isArray() || addresses.isEmpty() || addresses.size() > 100) throw invalid();
            Set<String> recipients = new TreeSet<>();
            for (JsonNode address : addresses) {
                String normalized = mailbox(address.asText());
                if (normalized.length() > 254 || !normalized.matches("[^\\s@<>]+@[^\\s@<>]+")) throw invalid();
                recipients.add(normalized);
            }
            return switch (type) {
                case "email.bounced" -> {
                    String detail = data.path("bounce").path("type").asText();
                    if (!Set.of("Permanent", "Transient", "Undetermined").contains(detail)) throw invalid();
                    yield new EmailFeedback("Bounce", providerId, detail, occurredAt, Set.copyOf(recipients),
                            detail.equals("Permanent") ? EmailSuppressionReason.PERMANENT_BOUNCE : null);
                }
                case "email.complained" -> new EmailFeedback("Complaint", providerId, "spam", occurredAt,
                        Set.copyOf(recipients), EmailSuppressionReason.COMPLAINT);
                default -> new EmailFeedback("Delivery", providerId, "delivered", occurredAt, Set.copyOf(recipients), null);
            };
        } catch (EmailNotificationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private static String mailbox(String address) {
        String value = address.strip().toLowerCase(Locale.ROOT);
        int opening = value.lastIndexOf('<');
        return opening >= 0 && value.endsWith(">") ? value.substring(opening + 1, value.length() - 1).strip() : value;
    }

    private static EmailNotificationException invalid() {
        return new EmailNotificationException(Reason.INVALID_INPUT, "Invalid Resend feedback event");
    }
}
