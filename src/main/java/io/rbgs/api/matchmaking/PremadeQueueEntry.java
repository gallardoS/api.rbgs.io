package io.rbgs.api.matchmaking;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import tools.jackson.databind.json.JsonMapper;

@Entity
@Table(name="premade_queue_entries", schema="rbgs")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PremadeQueueEntry {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Id private UUID id;
    @Column(name="group_id", nullable=false, updatable=false) private UUID groupId;
    @Column(name="entered_at", nullable=false, updatable=false) private Instant enteredAt;
    @Column(nullable=false, columnDefinition="text", updatable=false) private String snapshot;
    @Column(nullable=false, length=16) private String state;
    @Column(name="proposal_id") private UUID proposalId;
    @Column(nullable=false,length=16,updatable=false) private String source="SIMULATION";
    @Version private long version;

    PremadeQueueEntry(PremadeQueueCandidate candidate) {
        id=UUID.randomUUID(); groupId=candidate.groupId(); enteredAt=candidate.enteredAt();
        snapshot=JSON.writeValueAsString(candidate); state="QUEUED";
    }
    PremadeQueueEntry(PremadeQueueCandidate candidate,String source) {
        this(candidate);
        if(!java.util.Set.of("SIMULATION","FOREVER").contains(source))throw new IllegalArgumentException("Unknown queue source");
        this.source=source;
    }
    public PremadeQueueCandidate candidate() { return JSON.readValue(snapshot, PremadeQueueCandidate.class); }
    void cancel() { state="CANCELLED"; }
    void propose(UUID proposal) { state="PROPOSED"; proposalId=proposal; }
}
