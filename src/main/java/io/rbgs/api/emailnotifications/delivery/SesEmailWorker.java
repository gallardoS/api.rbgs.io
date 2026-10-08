package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SesEmailWorker {
    private final EmailNotificationSettings settings;
    private final EmailOutbox outbox;
    private final EmailSender sender;
    private final EmailRetentionService retention;

    public SesEmailWorker(EmailNotificationSettings settings, EmailOutbox outbox, EmailSender sender,
            EmailRetentionService retention) {
        this.settings = settings;
        this.outbox = outbox;
        this.sender = sender;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${rbgs.notifications.poll-ms:2000}")
    public void dispatch() {
        if (!settings.enabled()) return;
        var delivery = outbox.claim();
        if (delivery == null || !outbox.canSend(delivery)) return;
        var result = sender.send(delivery);
        switch (result.outcome()) {
            case ACCEPTED -> outbox.sent(delivery, result.providerId());
            case RETRY -> outbox.failed(delivery, false);
            case REVIEW -> outbox.failed(delivery, true);
        }
    }

    @Scheduled(fixedDelayString = "${rbgs.notifications.purge-interval:1h}", initialDelayString = "${rbgs.notifications.purge-initial-delay:1m}")
    public void purge() { retention.purge(); }
}
