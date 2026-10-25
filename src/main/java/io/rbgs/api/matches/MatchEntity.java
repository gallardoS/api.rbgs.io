package io.rbgs.api.matches;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "matches", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchEntity {
    @Id private UUID id;
    @Column(name = "created_by", updatable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "capture_start_deadline", nullable = false, updatable = false) private Instant captureStartDeadline;
    @Column(name = "expected_roster", nullable = false, updatable = false, columnDefinition = "text") private String expectedRoster;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(nullable = false, length = 32, updatable = false) private String profile;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false) private MatchMode mode;
    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_policy", nullable = false, length = 24, updatable = false) private MatchEvidencePolicy evidencePolicy;
    @Column(name = "verification_status", nullable = false, length = 16) private String verificationStatus = "PENDING";
    @Column(name = "verification_reason", length = 128) private String verificationReason;
    @Column(name = "outcome_kind", length = 8) private String outcomeKind;
    @Column(name = "winning_team", length = 1) private String winningTeam;
    @Column(name = "reports_a", nullable = false) private int reportsA;
    @Column(name = "reports_b", nullable = false) private int reportsB;
    @Column(name = "verification_deadline") private Instant verificationDeadline;
    @Version @Column(name = "result_version", nullable = false) private long resultVersion;
    @Column(name = "decision_reason", length = 2000) private String decisionReason;
    @Column(name = "decision_at") private Instant decisionAt;
    @Column(name = "decision_evidence_ids", columnDefinition = "text") private String decisionEvidenceIds;

    public void decision(String reason, String evidenceIds, Instant at) {
        decisionReason = reason;
        decisionEvidenceIds = evidenceIds;
        decisionAt = at;
    }

    public MatchEntity(UUID actor, Instant createdAt, Instant captureStartDeadline, String roster,
            MatchMode mode, MatchEvidencePolicy evidencePolicy, String profile, Instant verificationDeadline) {
        this.id = UUID.randomUUID();
        this.createdBy = actor;
        this.createdAt = java.util.Objects.requireNonNull(createdAt);
        this.captureStartDeadline = java.util.Objects.requireNonNull(captureStartDeadline);
        this.verificationDeadline = java.util.Objects.requireNonNull(verificationDeadline);
        this.expectedRoster = java.util.Objects.requireNonNull(roster);
        this.mode = java.util.Objects.requireNonNull(mode);
        this.evidencePolicy = java.util.Objects.requireNonNull(evidencePolicy);
        this.profile = java.util.Objects.requireNonNull(profile);
    }

    public void cancel(Instant at) { if (cancelledAt == null) cancelledAt = at; }

    public void verificationDeadline(Instant at) {
        if (verificationDeadline == null || at.isBefore(verificationDeadline)) verificationDeadline = at;
    }

    public void result(String status, String reason, String outcome, String winner, int a, int b) {
        verificationStatus = status;
        verificationReason = reason;
        outcomeKind = outcome;
        winningTeam = winner;
        reportsA = a;
        reportsB = b;
    }
}
