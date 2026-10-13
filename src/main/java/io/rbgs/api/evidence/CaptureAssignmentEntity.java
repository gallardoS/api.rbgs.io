package io.rbgs.api.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "capture_assignments", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureAssignmentEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false, updatable = false) private CaptureTestEntity testMatch;
    @Column(name = "account_id", nullable = false, updatable = false) private UUID accountId;
    @Column(name = "capture_id", nullable = false, updatable = false) private UUID captureId;
    @Column(name = "character_id", nullable = false, updatable = false) private UUID characterId;
    @Column(name = "assigned_team", length = 1, updatable = false) private String assignedTeam;
    @Column(name = "code_hash", length = 64) private String codeHash;
    @Column(name = "issued_at") private Instant issuedAt;

    public CaptureAssignmentEntity(CaptureTestEntity test, UUID account, UUID character, String team) {
        this.id = UUID.randomUUID();
        this.captureId = UUID.randomUUID();
        this.testMatch = test;
        this.accountId = account;
        this.characterId = character;
        this.assignedTeam = team;
    }

    public void issue(String hash, Instant at) {
        if (codeHash != null) throw new IllegalStateException("Assignment code already issued");
        this.codeHash = hash;
        this.issuedAt = at;
    }
}
