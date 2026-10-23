package io.rbgs.api.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "capture_processing_jobs", schema = "rbgs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureProcessingJob {
    @Id @Column(name = "evidence_id") private UUID evidenceId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public CaptureProcessingJob(UUID evidence, Instant at) {
        this.evidenceId = evidence;
        this.createdAt = at;
    }
}
