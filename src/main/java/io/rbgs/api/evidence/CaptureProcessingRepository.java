package io.rbgs.api.evidence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptureProcessingRepository extends JpaRepository<CaptureProcessingJob, UUID> {}
