package io.rbgs.api.matches;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "match_dispute_messages", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchDisputeMessage {
    @Id private UUID id;
    @Column(name = "dispute_id", nullable = false, updatable = false) private UUID disputeId;
    @Column(name = "author_id", nullable = false, updatable = false) private UUID authorId;
    @Column(name = "author_role", nullable = false, updatable = false, length = 16) private String authorRole;
    @Column(nullable = false, updatable = false, length = 32) private String kind;
    @Column(nullable = false, updatable = false, length = 2000) private String body;
    @Column(name = "recorded_at", nullable = false, updatable = false) private Instant recordedAt;
    public MatchDisputeMessage(UUID dispute, UUID author, String role, String kind, String body, Instant at) {
        id = UUID.randomUUID(); disputeId = dispute; authorId = author; authorRole = role;
        this.kind = kind; this.body = body; recordedAt = at;
    }
}
