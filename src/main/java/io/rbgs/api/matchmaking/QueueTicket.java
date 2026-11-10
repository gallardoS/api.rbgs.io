package io.rbgs.api.matchmaking;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import tools.jackson.databind.json.JsonMapper;

@Entity
@Table(name = "queue_tickets", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QueueTicket {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Id private UUID id;
    @Column(name="account_id", nullable=false, updatable=false) private UUID accountId;
    @Column(name="character_id", nullable=false, updatable=false) private UUID characterId;
    @Column(name="season_id", nullable=false, updatable=false) private UUID seasonId;
    @Column(name="queue_type", nullable=false, length=16, updatable=false) private String queueType;
    @Column(nullable=false, length=16) private String state;
    @Column(name="entered_at", nullable=false, updatable=false) private Instant enteredAt;
    @Column(nullable=false, columnDefinition="text", updatable=false) private String snapshot;
    @Column(name="proposal_id") private UUID proposalId;
    @Column(name="accepted_at") private Instant acceptedAt;
    @Column(nullable=false,length=16,updatable=false) private String source="SIMULATION";
    @Column(name="season_version",updatable=false) private Long seasonVersion;
    @Column(name="selection_version",updatable=false) private Long selectionVersion;
    @Column(name="companion_device_id",updatable=false) private UUID companionDeviceId;
    @Column(name="test_bot",updatable=false,nullable=false) private boolean testBot;
    @Column(name="cancellation_reason",length=64) private String cancellationReason;
    @Column(name="premade_entry_id",updatable=false) private UUID premadeEntryId;
    @Version private long version;

    QueueTicket(SoloQueueCandidate candidate) {
        if (candidate.captainLottery()==null) candidate=candidate.withCaptainLottery(UUID.randomUUID());
        id = UUID.randomUUID(); accountId = candidate.accountId(); characterId = candidate.characterId();
        seasonId = candidate.seasonId(); enteredAt = candidate.enteredAt(); queueType = "SOLO"; state = "QUEUED";
        snapshot = JSON.writeValueAsString(candidate);
    }
    SoloQueueCandidate candidate() {
        var candidate=JSON.readValue(snapshot, SoloQueueCandidate.class);
        // Older tickets already have a persisted random ID, so recovery must never reroll their tie-break.
        return candidate.captainLottery()==null?candidate.withCaptainLottery(id):candidate;
    }
    QueueTicket(SoloQueueCandidate candidate, UUID entryId) {
        this(candidate);
        queueType="PREMADE";
        premadeEntryId=java.util.Objects.requireNonNull(entryId);
    }
    void cancel() { if (!state.equals("QUEUED")) throw new IllegalStateException("Ticket is already reserved for a match"); state = "CANCELLED"; }
    void realAdmission(SoloQueueEligibility.Result eligibility) {
        realAdmission(eligibility.seasonVersion(),eligibility.selectionVersion(),eligibility.deviceId());
    }
    void realAdmission(Long season,Long selection,UUID device) {
        source="FOREVER";seasonVersion=java.util.Objects.requireNonNull(season);selectionVersion=java.util.Objects.requireNonNull(selection);
        companionDeviceId=device;testBot=device==null;
    }
    void invalidate() { invalidate("ELIGIBILITY_CHANGED"); }
    void invalidate(String reason) { cancel();cancellationReason=java.util.Objects.requireNonNull(reason); }
    void propose(UUID id) {
        if (!state.equals("QUEUED")) throw new IllegalStateException("Ticket is no longer waiting");
        proposalId = id; state = "PROPOSED";
    }
    void accept(Instant now) {
        if (!state.equals("PROPOSED")) throw new IllegalStateException("Ticket is not in a proposed room");
        if (acceptedAt == null) acceptedAt=now;
    }
    void releaseRoom() {
        if (!state.equals("PROPOSED")) throw new IllegalStateException("Ticket is not reserved for a room");
        state="CANCELLED";
    }
}
