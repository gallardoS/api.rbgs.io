package io.rbgs.api.evidence;

import io.rbgs.api.matches.MatchAssignmentEntity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "capture_evidence", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureEvidenceEntity {
    @Id private UUID id;
    @Column(name = "account_id", nullable = false, updatable = false) private UUID accountId;
    @Column(name = "device_id", nullable = false, updatable = false) private UUID deviceId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false) private MatchAssignmentEntity assignment;
    @Column(name = "capture_id", nullable = false, updatable = false) private UUID captureId;
    @Column(nullable = false, updatable = false) private int revision;
    @Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;
    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false) private String contentSha256;
    @Column(name = "stored_sha256", nullable = false, length = 64, updatable = false) private String storedSha256;
    @Column(name = "redacted_payload", nullable = false, updatable = false, columnDefinition = "text") private String redactedPayload;
    @Column(name = "processing_status", nullable = false, length = 16) private String processingStatus;
    @Column(name = "review_reason", length = 128) private String reviewReason;
    @Column(name = "processed_at") private Instant processedAt;
    @Column(name = "validation_status", nullable = false, length = 16) private String validationStatus;
    @Column(name = "normalized_facts", columnDefinition = "text") private String normalizedFacts;

    public CaptureEvidenceEntity(UUID account, UUID device, MatchAssignmentEntity assignment, int revision,
            Instant at, String contentHash, String storedHash, String payload, String reason) {
        this.id = UUID.randomUUID();
        this.accountId = account;
        this.deviceId = device;
        this.assignment = assignment;
        this.captureId = assignment.getCaptureId();
        this.revision = revision;
        this.receivedAt = at;
        this.contentSha256 = contentHash;
        this.storedSha256 = storedHash;
        this.redactedPayload = payload;
        this.processingStatus = "PENDING";
        this.validationStatus = "PENDING";
        this.reviewReason = reason;
    }

    public void processed(String validation, String reason, String facts, Instant at) {
        this.validationStatus = validation;
        this.reviewReason = reason;
        this.normalizedFacts = facts;
        this.processedAt = at;
        this.processingStatus = "REVIEW";
    }
}
