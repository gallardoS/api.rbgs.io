package io.rbgs.api.emailnotifications.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailFeedbackEventRepository extends JpaRepository<EmailFeedbackEventEntity, UUID> {
    long deleteByReceivedAtBefore(Instant cutoff);
}
