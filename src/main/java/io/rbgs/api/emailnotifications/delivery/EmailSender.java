package io.rbgs.api.emailnotifications.delivery;

public interface EmailSender {
    EmailSendResult send(EmailOutbox.Delivery delivery);
}
