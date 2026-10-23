package io.rbgs.api.matches;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "match_result_events", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchResultEvent {
    @Id private UUID id;
    @Column(name = "match_id", nullable = false, updatable = false) private UUID matchId;
    @Column(name = "recorded_at", nullable = false, updatable = false) private Instant recordedAt;
    @Column(name = "previous_status", nullable = false, length = 16, updatable = false) private String previousStatus;
    @Column(nullable = false, length = 16, updatable = false) private String status;
    @Column(length = 128, updatable = false) private String reason;
    @Column(name = "outcome_kind", length = 8, updatable = false) private String outcomeKind;
    @Column(name = "winning_team", length = 1, updatable = false) private String winningTeam;
    @Column(name = "evidence_ids", nullable = false, updatable = false, columnDefinition = "text") private String evidenceIds;
    @Column(name = "policy_version", nullable = false, length = 32, updatable = false) private String policyVersion;

    public MatchResultEvent(MatchEntity match, String previous, String evidenceIds, Instant at) {
        this.id = UUID.randomUUID();
        this.matchId = match.getId();
        this.recordedAt = at;
        this.previousStatus = previous;
        this.status = match.getVerificationStatus();
        this.reason = match.getVerificationReason();
        this.outcomeKind = match.getOutcomeKind();
        this.winningTeam = match.getWinningTeam();
        this.evidenceIds = evidenceIds;
        this.policyVersion = MatchVerification.POLICY;
    }
}
