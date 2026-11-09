package io.rbgs.api.matches;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

/** Queue-to-room lifecycle, separate from evidence verification and rating. */
@Entity
@Table(name="match_rooms",schema="rbgs")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class MatchRoomEntity {
    @Id private UUID id;
    @Column(name="season_id",nullable=false,updatable=false) private UUID seasonId;
    @Column(nullable=false,length=16,updatable=false) private String source;
    @Column(nullable=false,length=24) private String status;
    @Column(name="acceptance_deadline",nullable=false,updatable=false) private Instant acceptanceDeadline;
    @Column(name="preparation_deadline") private Instant preparationDeadline;
    @Column(name="captain_a",nullable=false,updatable=false) private UUID captainA;
    @Column(name="captain_b",nullable=false,updatable=false) private UUID captainB;
    @Column(name="cancellation_reason",length=64) private String cancellationReason;
    @Column(name="accepted_count",nullable=false) private int acceptedCount;
    @Version private long version;
    @Column(name="match_id") private UUID matchId;
    @Column(name="captain_a_ready",nullable=false) private boolean captainAReady;
    @Column(name="captain_b_ready",nullable=false) private boolean captainBReady;
    @Column(name="simulated_winner",length=1) private String simulatedWinner;

    public MatchRoomEntity(UUID proposal,UUID season,UUID captainA,UUID captainB,Instant now) {
        this(proposal,season,captainA,captainB,now,"SIMULATION");
    }
    public MatchRoomEntity(UUID proposal,UUID season,UUID captainA,UUID captainB,Instant now,String source) {
        id=proposal; seasonId=season; this.captainA=captainA; this.captainB=captainB;
        if(!java.util.Set.of("SIMULATION","FOREVER").contains(source))throw new IllegalArgumentException("Unknown room source");
        this.source=source; status="PROPOSED"; acceptanceDeadline=now.plusSeconds(60);
    }
    public void linkMatch(UUID id) { if(matchId!=null&&!matchId.equals(id))throw new IllegalStateException("Room already linked");matchId=java.util.Objects.requireNonNull(id); }
    public void preparing(Instant now) {
        preparing(now, 600);
    }
    public void preparing(Instant now, long seconds) {
        if (!status.equals("PROPOSED") || !now.isBefore(acceptanceDeadline)) throw new IllegalStateException("Acceptance deadline passed");
        status="PREPARING"; preparationDeadline=now.plusSeconds(seconds);
    }
    public void acceptanceChanged() { acceptedCount++; }

    public void confirmSimulatedPreparation(UUID captain) {
        requireSimulation();
        if (!captainA.equals(captain) && !captainB.equals(captain))
            throw new SecurityException("Only a match captain can confirm preparation");
        if (status.equals("PLAYING")) return;
        requireStatus("PREPARING");
        if (captainA.equals(captain)) captainAReady = true;
        else captainBReady = true;
        if (captainAReady && captainBReady) status = "PLAYING";
    }

    public boolean endSimulatedPlay() {
        requireSimulation();
        if (java.util.Set.of("AWAITING_EVIDENCE","UNDER_REVIEW","RESOLVED").contains(status)) return false;
        requireStatus("PLAYING");
        status = "AWAITING_EVIDENCE";
        return true;
    }

    public void submitSimulatedEvidence(String winner) {
        requireSimulation();
        if (!"A".equals(winner) && !"B".equals(winner)) throw new IllegalArgumentException("Choose a winning team");
        if ((status.equals("UNDER_REVIEW") || status.equals("RESOLVED")) && winner.equals(simulatedWinner)) return;
        requireStatus("AWAITING_EVIDENCE");
        simulatedWinner = winner;
        status = "UNDER_REVIEW";
    }

    public void resolveSimulation() {
        requireSimulation();
        if (status.equals("RESOLVED")) return;
        requireStatus("UNDER_REVIEW");
        status = "RESOLVED";
    }

    private void requireSimulation() {
        if (!source.equals("SIMULATION") || matchId != null)
            throw new IllegalStateException("This action is only available in the simulator");
    }

    private void requireStatus(String expected) {
        if (!status.equals(expected)) throw new IllegalStateException("This action is unavailable in the current room state");
    }

    public boolean due(Instant now) {
        return status.equals("PROPOSED") && !now.isBefore(acceptanceDeadline)
                || status.equals("PREPARING") && !now.isBefore(preparationDeadline);
    }
    public void cancel(String reason) { status="CANCELLED"; cancellationReason=reason; }
}
