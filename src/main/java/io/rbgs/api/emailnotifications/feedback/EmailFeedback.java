package io.rbgs.api.emailnotifications.feedback;

import java.time.Instant;
import java.util.Set;

public record EmailFeedback(String type, String providerId, String detail, Instant occurredAt,
        Set<String> recipients, EmailSuppressionReason suppressionReason) {}
