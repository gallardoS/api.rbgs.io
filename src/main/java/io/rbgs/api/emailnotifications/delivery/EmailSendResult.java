package io.rbgs.api.emailnotifications.delivery;

public record EmailSendResult(Outcome outcome, String providerId) {
    public enum Outcome { ACCEPTED, RETRY, REVIEW }
}
