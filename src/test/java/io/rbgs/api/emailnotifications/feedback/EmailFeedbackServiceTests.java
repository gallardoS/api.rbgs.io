package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.delivery.*;
import io.rbgs.api.emailnotifications.persistence.*;
import io.rbgs.api.emailnotifications.subscription.EmailSubscriptionService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class EmailFeedbackServiceTests {
    @Autowired EmailFeedbackService feedback;
    @Autowired EmailSubscriptionService subscriptions;
    @Autowired EmailOutbox outbox;
    @Autowired io.rbgs.api.emailnotifications.delivery.EmailRetentionService retention;
    @Autowired EmailSuppressionRepository suppressions;
    @Autowired EmailFeedbackEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailNotificationSettings settings;
    @MockitoBean SesEmailWorker worker;
    @MockitoBean software.amazon.awssdk.services.sesv2.SesV2Client sesClient;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM rbgs.email_outbox");
        jdbc.update("DELETE FROM rbgs.email_subscriptions");
        jdbc.update("DELETE FROM rbgs.email_campaigns");
        jdbc.update("DELETE FROM rbgs.email_suppressions");
        jdbc.update("DELETE FROM rbgs.email_feedback_events");
        when(settings.enabled()).thenReturn(true);
        when(settings.webOrigin()).thenReturn("http://localhost:5173");
        when(settings.tokenSecret()).thenReturn("test-secret-that-is-at-least-32-characters");
        when(settings.dailyLimit()).thenReturn(90);
    }

    @Test void permanentBounceCancelsQueuedMailAndBlocksFreshSignupEvenAfterSubscriberDeletion() {
        subscriptions.subscribe("Player@Example.com", "en", "");
        UUID event = UUID.randomUUID();
        var bounced = event("Bounce", "Permanent", Set.of("player@example.com"), EmailSuppressionReason.PERMANENT_BOUNCE);
        feedback.accept(event, bounced);
        Instant firstUpdate = suppressions.findById("player@example.com").orElseThrow().getUpdatedAt();
        feedback.accept(event, bounced);
        assertEquals(1, events.count());
        assertEquals(firstUpdate, suppressions.findById("player@example.com").orElseThrow().getUpdatedAt());
        assertNull(outbox.claim());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM rbgs.email_outbox", String.class));
        assertNull(jdbc.queryForObject("SELECT plain_text FROM rbgs.email_outbox", String.class));
        jdbc.update("DELETE FROM rbgs.email_subscriptions");
        subscriptions.subscribe("PLAYER@example.com", "en", "");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rbgs.email_subscriptions", Integer.class));
        assertEquals(1, suppressions.count());
    }

    @Test void complaintCancelsInflightDeliveryAndAWeakerBounceCannotReplaceIt() {
        subscriptions.subscribe("one@example.com", "en", "");
        var delivery = outbox.claim();
        assertNotNull(delivery);
        assertTrue(outbox.canSend(delivery));
        feedback.accept(UUID.randomUUID(), event("Complaint", "abuse", Set.of("one@example.com"), EmailSuppressionReason.COMPLAINT));
        assertFalse(outbox.canSend(delivery));
        feedback.accept(UUID.randomUUID(), event("Bounce", "Permanent", Set.of("one@example.com"), EmailSuppressionReason.PERMANENT_BOUNCE));
        assertEquals(EmailSuppressionReason.COMPLAINT, suppressions.findById("one@example.com").orElseThrow().getReason());
    }

    @Test void transientBounceAndDeliveryAreRecordedWithoutSuppressionOrAutomaticResend() {
        subscriptions.subscribe("one@example.com", "en", "");
        feedback.accept(UUID.randomUUID(), event("Bounce", "Transient", Set.of("one@example.com"), null));
        feedback.accept(UUID.randomUUID(), event("Delivery", "delivered", Set.of("one@example.com"), null));
        assertEquals(2, events.count());
        assertEquals(0, suppressions.count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rbgs.email_outbox", Integer.class));
        assertNotNull(outbox.claim());
    }

    @Test void supportsMultipleRecipientsAndBothSesPayloadFormats() {
        var json = JsonMapper.builder().build();
        for (String field : List.of("notificationType", "eventType")) {
            var parsed = new SesFeedbackParser().parse(json.readTree("""
                    {"%s":"Bounce","mail":{"messageId":"ses-id","sendingAccountId":"123456789012",
                    "destination":["One@example.com","two@example.com"]},"bounce":{"bounceType":"Permanent",
                    "timestamp":"2026-10-08T12:00:00Z","bouncedRecipients":[{"emailAddress":"one@example.com"},{"emailAddress":"two@example.com"}]}}
                    """.formatted(field)), "123456789012");
            feedback.accept(UUID.randomUUID(), parsed);
        }
        assertEquals(2, suppressions.count());
    }

    @Test void rejectsForeignAccountAndRecipientsOutsideOriginalDestination() {
        var json = JsonMapper.builder().build();
        String body = """
                {"notificationType":"Complaint","mail":{"messageId":"ses-id","sendingAccountId":"123456789012",
                "destination":["one@example.com"]},"complaint":{"timestamp":"2026-10-08T12:00:00Z",
                "complainedRecipients":[{"emailAddress":"other@example.com"}]}}
                """;
        assertThrows(EmailNotificationException.class, () -> new SesFeedbackParser().parse(json.readTree(body), "123456789012"));
        assertThrows(EmailNotificationException.class, () -> new SesFeedbackParser().parse(json.readTree(body), "999999999999"));
        assertEquals(0, suppressions.count());
    }

    @Test void purgeRetainsSuppressionsButDeletesOldFeedbackAudit() {
        UUID id = UUID.randomUUID();
        feedback.accept(id, event("Bounce", "Permanent", Set.of("one@example.com"), EmailSuppressionReason.PERMANENT_BOUNCE));
        var row = events.findById(id).orElseThrow();
        row.setReceivedAt(Instant.now().minusSeconds(91 * 86400L));
        events.saveAndFlush(row);
        retention.purge();
        assertEquals(0, events.count());
        assertEquals(1, suppressions.count());
    }

    private SesFeedback event(String type, String detail, Set<String> recipients, EmailSuppressionReason reason) {
        return new SesFeedback(type, "ses-id", detail, Instant.now(), recipients, reason);
    }
}
