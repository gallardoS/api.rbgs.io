package io.rbgs.api.emailnotifications.config;



import java.time.Duration;

public final class EmailPolicy {
    public static final Duration CONFIRMATION_LIFETIME = Duration.ofHours(48);
    public static final Duration SIGNUP_COOLDOWN = Duration.ofMinutes(15);
    public static final Duration DELIVERY_LEASE = Duration.ofMinutes(2);
    public static final Duration QUOTA_WINDOW = Duration.ofDays(1);
    public static final Duration FEEDBACK_RETENTION = Duration.ofDays(90);
    public static final Duration PENDING_RETENTION = Duration.ofDays(7);
    public static final Duration COMPLETED_RETENTION = Duration.ofDays(30);
    public static final Duration RETRY_BASE = Duration.ofMinutes(1);
    public static final Duration RETRY_MAX = Duration.ofHours(1);
    private EmailPolicy() {}
}
