package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.config.EmailPolicy;
import io.rbgs.api.emailnotifications.persistence.EmailOutboxRepository;
import io.rbgs.api.emailnotifications.persistence.EmailSubscriptionRepository;
import io.rbgs.api.emailnotifications.persistence.EmailSuppressionRepository;
import io.rbgs.api.emailnotifications.persistence.EmailFeedbackEventRepository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EmailOutbox {
    private final EmailOutboxRepository outbox;
    private final EmailSubscriptionRepository subscriptions;
    private final EmailNotificationSettings settings;
    private final EmailSuppressionRepository suppressions;
    private final EmailFeedbackEventRepository feedbackEvents;
    public EmailOutbox(EmailOutboxRepository outbox, EmailSubscriptionRepository subscriptions, EmailNotificationSettings settings, EmailSuppressionRepository suppressions, EmailFeedbackEventRepository feedbackEvents) {
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.suppressions = suppressions;
        this.feedbackEvents = feedbackEvents;
    }

    @Transactional
    public Delivery claim() {
        outbox.lockReservations();
        outbox.cancelInvalid(Instant.now());
        outbox.cancelSuppressed();
        outbox.reviewExpiredLeases(Instant.now());
        boolean quota = outbox.countByFirstAttemptAtAfter(Instant.now().minus(EmailPolicy.QUOTA_WINDOW)) >= settings.dailyLimit();
        var rows = outbox.findNext(quota, settings.seasonLive());
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        var delivery = new Delivery(row.getId(), row.getSubscription().getEmail(), row.getSubject(), row.getHtml(),
                row.getPlainText(), row.getAttempts() + 1, row.getFirstAttemptAt() == null ? Instant.now() : row.getFirstAttemptAt());
        row.setStatus(EmailStatus.INFLIGHT);
        row.setAttempts(delivery.attempts());
        row.setFirstAttemptAt(delivery.firstAttempt());
        row.setNextAttemptAt(Instant.now().plus(EmailPolicy.DELIVERY_LEASE));
        outbox.flush();
        return delivery;
    }

    public boolean canSend(Delivery delivery) {
        return !suppressions.existsById(delivery.email()) && outbox.existsByIdAndStatus(delivery.id(), EmailStatus.INFLIGHT);
    }

    public void sent(Delivery delivery, String providerId) { outbox.markSent(providerId, delivery.id(), Instant.now()); }

    public void failed(Delivery delivery, boolean permanent) {
        int delay = (int) Math.min(EmailPolicy.RETRY_MAX.toSeconds(), EmailPolicy.RETRY_BASE.toSeconds() * (1L << Math.min(delivery.attempts(), 6)));
        outbox.markFailed(permanent ? EmailStatus.REVIEW : EmailStatus.PENDING, Instant.now().plusSeconds(delay), delivery.id());
    }

    public record Delivery(UUID id, String email, String subject, String html, String text, int attempts, Instant firstAttempt) {}

    @Transactional
    public void purge() {
        subscriptions.purgeExpired(Instant.now().minus(EmailPolicy.PENDING_RETENTION), Instant.now().minus(EmailPolicy.COMPLETED_RETENTION));
        outbox.scrubReviewPayloads(Instant.now().minus(EmailPolicy.PENDING_RETENTION));
        feedbackEvents.deleteByReceivedAtBefore(Instant.now().minus(EmailPolicy.FEEDBACK_RETENTION));
    }
}
