package io.rbgs.api.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "capture_test_matches", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureTestEntity {
    @Id private UUID id;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "arm_until", nullable = false, updatable = false) private Instant armUntil;
    @Column(name = "expected_roster", nullable = false, updatable = false, columnDefinition = "text") private String expectedRoster;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(nullable = false, length = 32, updatable = false) private String profile;
    @Enumerated(EnumType.STRING)
    @Column(name = "test_kind", nullable = false, length = 24, updatable = false) private CaptureTestKind testKind;

    public CaptureTestEntity(UUID actor, Instant createdAt, Instant armUntil, String roster, CaptureTestKind kind) {
        this.id = UUID.randomUUID();
        this.createdBy = actor;
        this.createdAt = createdAt;
        this.armUntil = armUntil;
        this.expectedRoster = roster;
        this.profile = "DARKSPEAR_DIAGNOSTIC";
        this.testKind = kind;
    }
}
