package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.config.EmailPolicy;
import io.rbgs.api.emailnotifications.persistence.EmailOutboxRepository;
import io.rbgs.api.emailnotifications.persistence.EmailSuppressionRepository;

import java.time.Instant;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EmailOutbox {
    private final Clock clock;
    private final EmailOutboxRepository outbox;
    private final EmailNotificationSettings settings;
    private final EmailSuppressionRepository suppressions;
    public EmailOutbox(EmailOutboxRepository outbox, EmailNotificationSettings settings, EmailSuppressionRepository suppressions, Clock clock) {
        this.clock = clock;
        this.outbox = outbox;
        this.settings = settings;
        this.suppressions = suppressions;
    }

    @Transactional
    public Delivery claim() {
        Instant now = clock.instant();
        outbox.lockReservations();
        outbox.cancelInvalid(now);
        outbox.cancelSuppressed();
        outbox.reviewExpiredLeases(now);
        boolean quota = outbox.countByFirstAttemptAtAfter(now.minus(EmailPolicy.QUOTA_WINDOW)) >= settings.dailyLimit();
        var rows = outbox.findNext(quota, settings.seasonLive(), now);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        var delivery = new Delivery(row.getId(), row.getSubscription().getEmail(), row.getSubject(), row.getHtml(),
                row.getPlainText(), row.getAttempts() + 1, row.getFirstAttemptAt() == null ? now : row.getFirstAttemptAt());
        row.setStatus(EmailStatus.INFLIGHT);
        row.setAttempts(delivery.attempts());
        row.setFirstAttemptAt(delivery.firstAttempt());
        row.setNextAttemptAt(now.plus(EmailPolicy.DELIVERY_LEASE));
        outbox.flush();
        return delivery;
    }

    public boolean canSend(Delivery delivery) {
        return !suppressions.existsById(delivery.email()) && outbox.existsByIdAndStatus(delivery.id(), EmailStatus.INFLIGHT);
    }

    public void sent(Delivery delivery, String providerId) { outbox.markSent(providerId, delivery.id(), clock.instant()); }

    public void failed(Delivery delivery, boolean permanent) {
        int delay = (int) Math.min(EmailPolicy.RETRY_MAX.toSeconds(), EmailPolicy.RETRY_BASE.toSeconds() * (1L << Math.min(delivery.attempts(), 6)));
        outbox.markFailed(permanent ? EmailStatus.REVIEW : EmailStatus.PENDING, clock.instant().plusSeconds(delay), delivery.id());
    }

    public record Delivery(UUID id, String email, String subject, String html, String text, int attempts, Instant firstAttempt) {}

}
