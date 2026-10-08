package io.rbgs.api.emailnotifications.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppressionEntity, String> {}
