package io.rbgs.api.emailnotifications.persistence;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "email_feedback_events", schema = "rbgs")
@Getter
@Setter
@NoArgsConstructor
public class EmailFeedbackEventEntity {
    @Id private UUID id;
    @Column(name = "provider_id", nullable = false, length = 100) private String providerId;
    @Column(name = "event_type", nullable = false, length = 24) private String eventType;
    @Column(nullable = false, length = 64) private String detail;
    @Column(name = "recipient_count", nullable = false) private int recipientCount;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
}
