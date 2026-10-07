package io.rbgs.api.emailnotifications.persistence;

import io.rbgs.api.emailnotifications.delivery.EmailKind;
import io.rbgs.api.emailnotifications.delivery.EmailStatus;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "email_outbox", schema = "rbgs")
@Getter
@Setter
@NoArgsConstructor
public class EmailOutboxEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false) private EmailSubscriptionEntity subscription;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private EmailKind kind;
    @Column(nullable = false, length = 200) private String subject;
    @Column(columnDefinition = "text") private String html;
    @Column(name = "plain_text", columnDefinition = "text") private String plainText;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private EmailStatus status = EmailStatus.PENDING;
    @Column(nullable = false) private int attempts;
    @Column(name = "first_attempt_at") private Instant firstAttemptAt;
    @Column(name = "next_attempt_at", nullable = false, insertable = false) private Instant nextAttemptAt;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "provider_id", length = 100) private String providerId;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
}
