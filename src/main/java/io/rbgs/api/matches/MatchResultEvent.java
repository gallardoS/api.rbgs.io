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
    @Column(name = "actor_id", updatable = false) private UUID actorId;
    @Column(name = "decision_reason", length = 2000, updatable = false) private String decisionReason;
    @Column(name = "expected_version", updatable = false) private Long expectedVersion;
    @Column(name = "previous_reason", length = 128, updatable = false) private String previousReason;
    @Column(name = "previous_outcome_kind", length = 8, updatable = false) private String previousOutcomeKind;
    @Column(name = "previous_winning_team", length = 1, updatable = false) private String previousWinningTeam;
    @Column(name = "correction_required", nullable = false, updatable = false) private boolean correctionRequired;

    public MatchResultEvent(MatchEntity match, String previous, String previousReason, String previousOutcome,
            String previousWinner, String evidenceIds, Instant at, UUID actor, long expectedVersion, boolean correction) {
        this(match, previous, evidenceIds, at);
        this.actorId = actor;
        this.decisionReason = match.getDecisionReason();
        this.expectedVersion = expectedVersion;
        this.previousReason = previousReason;
        this.previousOutcomeKind = previousOutcome;
        this.previousWinningTeam = previousWinner;
        this.correctionRequired = correction;
    }

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
