package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.persistence.EmailOutboxEntity;
import io.rbgs.api.emailnotifications.persistence.EmailOutboxRepository;
import io.rbgs.api.emailnotifications.persistence.EmailSubscriptionRepository;
import io.rbgs.api.emailnotifications.subscription.EmailTokens;
import io.rbgs.api.emailnotifications.template.EmailMessage;
import io.rbgs.api.emailnotifications.template.EmailTemplates;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class EmailQueue {
    private final EmailOutboxRepository outbox;
    private final EmailSubscriptionRepository subscriptions;
    private final EmailTokens tokens;
    private final EmailTemplates templates;
    public EmailQueue(EmailOutboxRepository outbox, EmailSubscriptionRepository subscriptions, EmailTokens tokens, EmailTemplates templates) {
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.tokens = tokens;
        this.templates = templates;
    }
    public void confirmation(UUID id, String language, String token) {
        enqueue(id, EmailKind.CONFIRMATION, templates.confirmation(language, token, unsubscribeToken(id)));
    }
    public void launch(UUID id, String language) {
        if (outbox.existsBySubscriptionIdAndKind(id, EmailKind.LAUNCH)) return;
        enqueue(id, EmailKind.LAUNCH, templates.launch(language, unsubscribeToken(id)));
    }
    private String unsubscribeToken(UUID id) {
        String token = tokens.unsubscribe(id);
        subscriptions.setUnsubscribeHash(id, EmailTokens.hash(token));
        return token;
    }
    private void enqueue(UUID id, EmailKind kind, EmailMessage message) {
        var email = new EmailOutboxEntity();
        email.setId(UUID.randomUUID());
        email.setSubscription(subscriptions.getReferenceById(id));
        email.setKind(kind);
        email.setSubject(message.subject());
        email.setHtml(message.html());
        email.setPlainText(message.text());
        outbox.saveAndFlush(email);
    }
}
