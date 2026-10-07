package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.config.EmailPolicy;
import io.rbgs.api.emailnotifications.persistence.EmailOutboxRepository;
import io.rbgs.api.emailnotifications.persistence.EmailSubscriptionRepository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EmailOutbox {
    private final EmailOutboxRepository outbox;
    private final EmailSubscriptionRepository subscriptions;
    private final EmailNotificationSettings settings;
    public EmailOutbox(EmailOutboxRepository outbox, EmailSubscriptionRepository subscriptions, EmailNotificationSettings settings) {
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.settings = settings;
    }

    @Transactional
    public Delivery claim() {
        outbox.lockReservations();
        outbox.cancelInvalid(Instant.now());
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
    }
}
