package io.rbgs.api.emailnotifications.subscription;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.delivery.EmailOutbox;
import io.rbgs.api.emailnotifications.delivery.EmailDeliveryWorker;

import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class EmailSubscriptionServiceTests {
    @Autowired EmailSubscriptionService service;
    @Autowired EmailOutbox outbox;
    @Autowired io.rbgs.api.emailnotifications.delivery.EmailRetentionService retention;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailNotificationSettings settings;
    @MockitoBean EmailDeliveryWorker worker;
    @MockitoBean software.amazon.awssdk.services.sesv2.SesV2Client sesClient;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM rbgs.email_outbox");
        jdbc.update("DELETE FROM rbgs.email_subscriptions");
        jdbc.update("DELETE FROM rbgs.email_campaigns");
        when(settings.enabled()).thenReturn(true);
        when(settings.webOrigin()).thenReturn("http://localhost:5173");
        when(settings.tokenSecret()).thenReturn("test-secret-that-is-at-least-32-characters");
        when(settings.dailyLimit()).thenReturn(90);
    }

    @Test void subscriptionConfirmationAndLaunchAreIdempotentAndRespectCancellation() {
        service.subscribe("Player@Example.com", "es", "");
        service.subscribe("player@example.com", "es", "");
        assertEquals(1, count("email_subscriptions"));
        assertEquals(1, count("email_outbox"));
        UUID id = jdbc.queryForObject("SELECT id FROM rbgs.email_subscriptions", UUID.class);
        String text = jdbc.queryForObject("SELECT plain_text FROM rbgs.email_outbox", String.class);
        String confirmation = token(text, "season-confirm");
        String unsubscribe = token(text, "season-unsubscribe");
        assertEquals(EmailTokens.hash(confirmation), jdbc.queryForObject("SELECT confirmation_hash FROM rbgs.email_subscriptions", String.class));
        assertThrows(EmailNotificationException.class, service::launch);
        service.confirm(confirmation);
        service.confirm(confirmation);
        when(settings.seasonLive()).thenReturn(true);
        assertEquals(1, service.launch());
        assertEquals(0, service.launch());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rbgs.email_outbox WHERE kind = 'LAUNCH'", Integer.class));
        String launch = jdbc.queryForObject("SELECT plain_text FROM rbgs.email_outbox WHERE kind = 'LAUNCH'", String.class);
        assertTrue(launch.contains("http://localhost:5173/es/play"));
        assertEquals(unsubscribe, token(launch, "season-unsubscribe"));
        service.unsubscribe(unsubscribe);
        service.unsubscribe(unsubscribe);
        assertNull(outbox.claim());
        assertThrows(EmailNotificationException.class, () -> service.confirm(confirmation));
        assertEquals(0, service.launch());
        assertNotNull(id);
    }

    @Test void expiredLinkFailsAndOnlyFreshExplicitSignupCanReactivateCancelledSubscription() {
        service.subscribe("player@example.com", "en", "");
        String firstText = jdbc.queryForObject("SELECT plain_text FROM rbgs.email_outbox", String.class);
        String old = token(firstText, "season-confirm");
        jdbc.update("UPDATE rbgs.email_subscriptions SET confirmation_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', requested_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'");
        assertThrows(EmailNotificationException.class, () -> service.confirm(old));
        service.unsubscribe(token(firstText, "season-unsubscribe"));
        service.subscribe("player@example.com", "en", "");
        assertThrows(EmailNotificationException.class, () -> service.confirm(old));
        EmailOutbox.Delivery delivery = outbox.claim();
        assertNotNull(delivery);
        String fresh = token(delivery.text(), "season-confirm");
        assertNotEquals(old, fresh);
        service.confirm(fresh);
        assertNotNull(jdbc.queryForObject("SELECT confirmed_at FROM rbgs.email_subscriptions", java.sql.Timestamp.class));
    }

    @Test void cancellingBeforeConfirmationInvalidatesTheStillUnexpiredConfirmationLink() {
        service.subscribe("one@example.com", "en", "");
        String text = jdbc.queryForObject("SELECT plain_text FROM rbgs.email_outbox", String.class);
        service.unsubscribe(token(text, "season-unsubscribe"));
        assertThrows(EmailNotificationException.class, () -> service.confirm(token(text, "season-confirm")));
        assertNull(outbox.claim());
    }

    @Test void confirmationAfterTheManualLaunchQueuesTheOneRequestedNotification() {
        service.subscribe("one@example.com", "en", "");
        String text = jdbc.queryForObject("SELECT plain_text FROM rbgs.email_outbox", String.class);
        when(settings.seasonLive()).thenReturn(true);
        assertEquals(0, service.launch());
        service.confirm(token(text, "season-confirm"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rbgs.email_outbox WHERE kind = 'LAUNCH'", Integer.class));
        assertEquals(0, service.launch());
    }

    @Test void retryUsesSamePayloadAndQuotaPersistsWithDeliveryReceipts() {
        when(settings.dailyLimit()).thenReturn(1);
        service.subscribe("one@example.com", "en", "");
        service.subscribe("two@example.com", "en", "");
        EmailOutbox.Delivery first = outbox.claim();
        assertNotNull(first);
        assertNull(outbox.claim());
        outbox.failed(first, false);
        jdbc.update("UPDATE rbgs.email_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", first.id());
        EmailOutbox.Delivery retry = outbox.claim();
        assertNotNull(retry);
        assertEquals(first.id(), retry.id());
        assertEquals(first.attempts() + 1, retry.attempts());
        assertEquals(first.html(), retry.html());
        outbox.sent(retry, "provider-id");
        assertNull(jdbc.queryForObject("SELECT html FROM rbgs.email_outbox WHERE id = ?", String.class, first.id()));
        assertNull(outbox.claim());
        jdbc.update("UPDATE rbgs.email_outbox SET first_attempt_at = CURRENT_TIMESTAMP - INTERVAL '25 hours' WHERE id = ?", first.id());
        assertNotNull(outbox.claim());
    }

    @Test void expiredInflightLeaseRequiresReviewAndRetentionRemovesEmails() {
        service.subscribe("one@example.com", "en", "");
        EmailOutbox.Delivery delivery = outbox.claim();
        assertNotNull(delivery);
        jdbc.update("UPDATE rbgs.email_outbox SET first_attempt_at = CURRENT_TIMESTAMP - INTERVAL '24 hours', next_attempt_at = CURRENT_TIMESTAMP");
        assertNull(outbox.claim());
        assertEquals("REVIEW", jdbc.queryForObject("SELECT status FROM rbgs.email_outbox", String.class));
        jdbc.update("UPDATE rbgs.email_subscriptions SET requested_at = CURRENT_TIMESTAMP - INTERVAL '8 days'");
        retention.purge();
        assertEquals(0, count("email_subscriptions"));
        assertEquals(0, count("email_outbox"));
    }

    @Test void lateWorkerResultsDoNotReactivateCancelledDelivery() {
        service.subscribe("one@example.com", "en", "");
        var delivery = outbox.claim();
        assertNotNull(delivery);
        service.unsubscribe(token(delivery.text(), "season-unsubscribe"));
        outbox.sent(delivery, "late-provider-id");
        outbox.failed(delivery, false);
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM rbgs.email_outbox WHERE id = ?", String.class, delivery.id()));
        assertNull(jdbc.queryForObject("SELECT html FROM rbgs.email_outbox WHERE id = ?", String.class, delivery.id()));
        assertNull(outbox.claim());
    }

    @Test void botsAndDisabledProviderDoNotCollectEmails() {
        service.subscribe("bot@example.com", "en", "https://spam.example");
        assertEquals(0, count("email_subscriptions"));
        when(settings.enabled()).thenReturn(false);
        assertThrows(EmailNotificationException.class, () -> service.subscribe("one@example.com", "en", ""));
        assertEquals(0, count("email_subscriptions"));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM rbgs." + table, Integer.class); }
    private String token(String text, String name) {
        var matcher = Pattern.compile(name + "=([A-Za-z0-9_-]{43})").matcher(text);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}
