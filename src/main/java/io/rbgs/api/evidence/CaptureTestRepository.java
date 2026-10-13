package io.rbgs.api.evidence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptureTestRepository extends JpaRepository<CaptureTestEntity, UUID> {}
