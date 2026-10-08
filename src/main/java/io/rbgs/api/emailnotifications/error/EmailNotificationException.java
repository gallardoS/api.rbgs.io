package io.rbgs.api.emailnotifications.error;

public class EmailNotificationException extends RuntimeException {
    public enum Reason { INVALID_INPUT, UNTRUSTED, UNAVAILABLE, CONFLICT, RATE_LIMITED }
    private final Reason reason;

    public EmailNotificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
