package io.rbgs.api.matchmaking;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import tools.jackson.databind.json.JsonMapper;

/** Durable handoff to feature 11; it is not an evidence assignment or a rated result. */
@Entity
@Table(name="queue_proposals", schema="rbgs")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class QueueProposal {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Id private UUID id;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(nullable=false, columnDefinition="text", updatable=false) private String snapshot;
    QueueProposal(SoloQueueMatcher.Proposal proposal, Instant now) {
        id = UUID.randomUUID(); createdAt = now; snapshot = JSON.writeValueAsString(proposal);
    }
    public SoloQueueMatcher.Proposal proposal() { return JSON.readValue(snapshot, SoloQueueMatcher.Proposal.class); }
}
