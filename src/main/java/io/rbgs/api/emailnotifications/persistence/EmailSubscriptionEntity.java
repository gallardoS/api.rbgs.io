package io.rbgs.api.emailnotifications.persistence;



import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "email_subscriptions", schema = "rbgs")
@Getter
@Setter
@NoArgsConstructor
public class EmailSubscriptionEntity {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(nullable = false, length = 2) private String language;
    @Column(name = "confirmation_hash", nullable = false, length = 64) private String confirmationHash;
    @Column(name = "confirmation_expires_at", nullable = false) private Instant confirmationExpiresAt;
    @Column(name = "unsubscribe_hash", length = 64) private String unsubscribeHash;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "unsubscribed_at") private Instant unsubscribedAt;
    @Column(name = "consent_version", insertable = false, updatable = false, length = 32) private String consentVersion;
}
