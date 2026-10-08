package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailPolicy;
import io.rbgs.api.emailnotifications.persistence.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailRetentionService {
    private final EmailSubscriptionRepository subscriptions;
    private final EmailOutboxRepository outbox;
    private final EmailFeedbackEventRepository feedbackEvents;
    private final Clock clock;

    public EmailRetentionService(EmailSubscriptionRepository subscriptions, EmailOutboxRepository outbox,
            EmailFeedbackEventRepository feedbackEvents, Clock clock) {
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.feedbackEvents = feedbackEvents;
        this.clock = clock;
    }

    @Transactional
    public void purge() {
        var now = clock.instant();
        subscriptions.purgeExpired(now.minus(EmailPolicy.PENDING_RETENTION), now.minus(EmailPolicy.COMPLETED_RETENTION));
        outbox.scrubReviewPayloads(now.minus(EmailPolicy.PENDING_RETENTION));
        feedbackEvents.deleteByReceivedAtBefore(now.minus(EmailPolicy.FEEDBACK_RETENTION));
    }
}
