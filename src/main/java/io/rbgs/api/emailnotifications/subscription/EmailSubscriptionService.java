package io.rbgs.api.emailnotifications.subscription;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.config.EmailPolicy;
import io.rbgs.api.emailnotifications.delivery.EmailKind;
import io.rbgs.api.emailnotifications.delivery.EmailQueue;
import io.rbgs.api.emailnotifications.persistence.EmailCampaignEntity;
import io.rbgs.api.emailnotifications.persistence.EmailCampaignRepository;
import io.rbgs.api.emailnotifications.persistence.EmailOutboxRepository;
import io.rbgs.api.emailnotifications.persistence.EmailSubscriptionRepository;
import io.rbgs.api.emailnotifications.persistence.EmailSuppressionRepository;

import java.time.Instant;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;

@Service
public class EmailSubscriptionService {
    private final Clock clock;
    private final EmailSubscriptionRepository subscriptions;
    private final EmailCampaignRepository campaigns;
    private final EmailOutboxRepository outbox;
    private final EmailNotificationSettings settings;
    private final EmailQueue mail;
    private final EmailSuppressionRepository suppressions;
    private final EmailTokens tokens;

    public EmailSubscriptionService(EmailSubscriptionRepository subscriptions, EmailCampaignRepository campaigns, EmailOutboxRepository outbox, EmailNotificationSettings settings, EmailQueue mail, EmailTokens tokens, EmailSuppressionRepository suppressions, Clock clock) {
        this.clock = clock;
        this.subscriptions = subscriptions;
        this.campaigns = campaigns;
        this.outbox = outbox;
        this.settings = settings;
        this.mail = mail;
        this.tokens = tokens;
        this.suppressions = suppressions;
    }

    @Transactional
    public void subscribe(String address, String language, String website) {
        Instant now = clock.instant();
        if (!website.isBlank()) return;
        requireEnabled();
        if (settings.seasonLive()) throw new EmailNotificationException(Reason.CONFLICT, "Season already started");
        String email = address.strip().toLowerCase(Locale.ROOT);
        if (suppressions.existsById(email)) return;
        String confirmation = tokens.confirmation();
        UUID id = UUID.randomUUID();
        subscriptions.insertIfAbsent(id, email, language, EmailTokens.hash(confirmation), now.plus(EmailPolicy.CONFIRMATION_LIFETIME));
        var row = subscriptions.findByEmail(email).orElseThrow();
        UUID existingId = row.getId();
        if (!existingId.equals(id)) {
            if (row.getConfirmedAt() != null && row.getUnsubscribedAt() == null) return;
            if (row.getRequestedAt().isAfter(now.minus(EmailPolicy.SIGNUP_COOLDOWN))) return;
            outbox.cancel(existingId, EmailKind.CONFIRMATION);
            row.setLanguage(language);
            row.setConfirmationHash(EmailTokens.hash(confirmation));
            row.setConfirmationExpiresAt(now.plus(EmailPolicy.CONFIRMATION_LIFETIME));
            row.setRequestedAt(now);
            row.setConfirmedAt(null);
            row.setUnsubscribedAt(null);
            subscriptions.saveAndFlush(row);
        }
        mail.confirmation(existingId, language, confirmation);
    }

    @Transactional
    public void confirm(String token) {
        Instant now = clock.instant();
        campaigns.lockLaunch();
        var row = subscriptions.findByConfirmationHash(EmailTokens.hash(token)).orElseThrow(this::invalidLink);
        if (row.getUnsubscribedAt() != null || suppressions.existsById(row.getEmail())) throw invalidLink();
        if (row.getConfirmedAt() != null) return;
        if (row.getConfirmationExpiresAt().isBefore(now)) throw invalidLink();
        row.setConfirmedAt(now);
        row.setUnsubscribedAt(null);
        subscriptions.flush();
        if (settings.seasonLive() && campaigns.existsById("first-launch")) mail.launch(row.getId(), row.getLanguage());
    }

    @Transactional
    public void unsubscribe(String token) {
        var row = subscriptions.findByUnsubscribeHash(EmailTokens.hash(token)).orElseThrow(this::invalidLink);
        row.setUnsubscribedAt(clock.instant());
        outbox.cancel(row.getId(), null);
    }

    @Transactional
    public int launch() {
        campaigns.lockLaunch();
        requireEnabled();
        if (!settings.seasonLive()) throw new EmailNotificationException(Reason.CONFLICT, "Verify play is available and enable season-live first");
        if (!campaigns.existsById("first-launch")) campaigns.saveAndFlush(new EmailCampaignEntity("first-launch"));
        var rows = subscriptions.findLaunchRecipients();
        for (var row : rows) mail.launch(row.getId(), row.getLanguage());
        return rows.size();
    }

    private void requireEnabled() {
        if (!settings.enabled()) throw new EmailNotificationException(Reason.UNAVAILABLE, "Notifications are not configured");
    }

    private EmailNotificationException invalidLink() { return new EmailNotificationException(Reason.INVALID_INPUT, "Invalid or expired link"); }

}
