package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailPolicy;
import io.rbgs.api.emailnotifications.persistence.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class EmailRetentionServiceTests {
    @Test void appliesRetentionUsingOneInjectedInstantWithoutDeletingSuppressions() {
        var subscriptions = mock(EmailSubscriptionRepository.class);
        var outbox = mock(EmailOutboxRepository.class);
        var events = mock(EmailFeedbackEventRepository.class);
        var now = Instant.parse("2026-10-08T12:00:00Z");
        new EmailRetentionService(subscriptions, outbox, events, Clock.fixed(now, ZoneOffset.UTC)).purge();
        verify(subscriptions).purgeExpired(now.minus(EmailPolicy.PENDING_RETENTION), now.minus(EmailPolicy.COMPLETED_RETENTION));
        verify(outbox).scrubReviewPayloads(now.minus(EmailPolicy.PENDING_RETENTION));
        verify(events).deleteByReceivedAtBefore(now.minus(EmailPolicy.FEEDBACK_RETENTION));
    }
}
