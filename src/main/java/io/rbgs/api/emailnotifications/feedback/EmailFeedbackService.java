package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.persistence.*;
import java.time.Instant;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class EmailFeedbackService {
    private static final Logger LOG = LoggerFactory.getLogger(EmailFeedbackService.class);
    private final Clock clock;
    private final EmailFeedbackEventRepository events;
    private final EmailSuppressionRepository suppressions;
    private final EmailOutboxRepository outbox;

    public EmailFeedbackService(EmailFeedbackEventRepository events, EmailSuppressionRepository suppressions, EmailOutboxRepository outbox, Clock clock) {
        this.clock = clock;
        this.events = events;
        this.suppressions = suppressions;
        this.outbox = outbox;
    }

    @Transactional
    public void accept(UUID messageId, SesFeedback feedback) {
        outbox.lockReservations();
        if (events.existsById(messageId)) return;
        Instant now = clock.instant();
        var event = new EmailFeedbackEventEntity();
        event.setId(messageId);
        event.setProviderId(feedback.providerId());
        event.setEventType(feedback.type());
        event.setDetail(feedback.detail());
        event.setRecipientCount(feedback.recipients().size());
        event.setOccurredAt(feedback.occurredAt());
        event.setReceivedAt(now);
        events.saveAndFlush(event);
        if (feedback.suppressionReason() != null) {
            for (String address : feedback.recipients()) {
                var suppression = suppressions.findById(address).orElseGet(() -> {
                    var row = new EmailSuppressionEntity();
                    row.setEmail(address);
                    row.setCreatedAt(now);
                    return row;
                });
                if (suppression.getReason() != EmailSuppressionReason.COMPLAINT) suppression.setReason(feedback.suppressionReason());
                suppression.setUpdatedAt(now);
                suppressions.saveAndFlush(suppression);
            }
            outbox.cancelSuppressed();
        }
        LOG.info("SES feedback {} processed (type={}; detail={}; recipients={}; suppressed={})", messageId,
                feedback.type(), feedback.detail(), feedback.recipients().size(), feedback.suppressionReason() != null);
    }
}
