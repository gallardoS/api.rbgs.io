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
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmailSubscriptionService {
    private final EmailSubscriptionRepository subscriptions;
    private final EmailCampaignRepository campaigns;
    private final EmailOutboxRepository outbox;
    private final EmailNotificationSettings settings;
    private final EmailQueue mail;
    private final EmailTokens tokens;
    private final EmailSuppressionRepository suppressions;

    public EmailSubscriptionService(EmailSubscriptionRepository subscriptions, EmailCampaignRepository campaigns, EmailOutboxRepository outbox, EmailNotificationSettings settings, EmailQueue mail, EmailTokens tokens, EmailSuppressionRepository suppressions) {
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
        if (!website.isBlank()) return;
        requireEnabled();
        if (settings.seasonLive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Season already started");
        String email = address.strip().toLowerCase(Locale.ROOT);
        if (suppressions.existsById(email)) return;
        String confirmation = tokens.confirmation();
        UUID id = UUID.randomUUID();
        subscriptions.insertIfAbsent(id, email, language, EmailTokens.hash(confirmation), Instant.now().plus(EmailPolicy.CONFIRMATION_LIFETIME));
        var row = subscriptions.findByEmail(email).orElseThrow();
        UUID existingId = row.getId();
        if (!existingId.equals(id)) {
            if (row.getConfirmedAt() != null && row.getUnsubscribedAt() == null) return;
            if (row.getRequestedAt().isAfter(Instant.now().minus(EmailPolicy.SIGNUP_COOLDOWN))) return;
            outbox.cancel(existingId, EmailKind.CONFIRMATION);
            row.setLanguage(language);
            row.setConfirmationHash(EmailTokens.hash(confirmation));
            row.setConfirmationExpiresAt(Instant.now().plus(EmailPolicy.CONFIRMATION_LIFETIME));
            row.setRequestedAt(Instant.now());
            row.setConfirmedAt(null);
            row.setUnsubscribedAt(null);
            subscriptions.saveAndFlush(row);
        }
        mail.confirmation(existingId, language, confirmation);
    }

    @Transactional
    public void confirm(String token) {
        campaigns.lockLaunch();
        var row = subscriptions.findByConfirmationHash(EmailTokens.hash(token)).orElseThrow(this::invalidLink);
        if (row.getUnsubscribedAt() != null || suppressions.existsById(row.getEmail())) throw invalidLink();
        if (row.getConfirmedAt() != null) return;
        if (row.getConfirmationExpiresAt().isBefore(Instant.now())) throw invalidLink();
        row.setConfirmedAt(Instant.now());
        row.setUnsubscribedAt(null);
        subscriptions.flush();
        if (settings.seasonLive() && campaigns.existsById("first-launch")) mail.launch(row.getId(), row.getLanguage());
    }

    @Transactional
    public void unsubscribe(String token) {
        var row = subscriptions.findByUnsubscribeHash(EmailTokens.hash(token)).orElseThrow(this::invalidLink);
        row.setUnsubscribedAt(Instant.now());
        outbox.cancel(row.getId(), null);
    }

    @Transactional
    public int launch() {
        campaigns.lockLaunch();
        requireEnabled();
        if (!settings.seasonLive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Verify play is available and enable season-live first");
        if (!campaigns.existsById("first-launch")) campaigns.saveAndFlush(new EmailCampaignEntity("first-launch"));
        var rows = subscriptions.findLaunchRecipients();
        for (var row : rows) mail.launch(row.getId(), row.getLanguage());
        return rows.size();
    }

    private void requireEnabled() {
        if (!settings.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Notifications are not configured");
    }

    private ResponseStatusException invalidLink() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired link"); }

}
