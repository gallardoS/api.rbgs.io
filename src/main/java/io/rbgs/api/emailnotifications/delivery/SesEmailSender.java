package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

@Component
public class SesEmailSender implements EmailSender {
    private static final Logger LOG = LoggerFactory.getLogger(SesEmailSender.class);
    private final EmailNotificationSettings settings;
    private final SesV2Client client;

    public SesEmailSender(EmailNotificationSettings settings, SesV2Client client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public EmailSendResult send(EmailOutbox.Delivery delivery) {
        try {
            var response = client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(settings.from())
                    .destination(d -> d.toAddresses(delivery.email()))
                    .emailTags(t -> t.name("outbox-id").value(delivery.id().toString()))
                    .content(c -> c.simple(m -> m.subject(content(delivery.subject()))
                            .body(b -> b.html(content(delivery.html())).text(content(delivery.text())))))
                    .build());
            if (response == null || response.messageId() == null || response.messageId().isBlank()) {
                return new EmailSendResult(EmailSendResult.Outcome.REVIEW, null);
            }
            return new EmailSendResult(EmailSendResult.Outcome.ACCEPTED, response.messageId());
        } catch (TooManyRequestsException error) {
            return new EmailSendResult(EmailSendResult.Outcome.RETRY, null);
        } catch (RuntimeException error) {
            if (error instanceof SesV2Exception sesError) {
                String code = sesError.awsErrorDetails() == null ? "unknown" : sesError.awsErrorDetails().errorCode();
                String detail = sesError.awsErrorDetails() == null ? "unavailable" : sesError.awsErrorDetails().errorMessage();
                detail = detail == null ? "unavailable" : detail
                        .replaceAll("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+", "[email]")
                        .replaceAll("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b", "[access-key]")
                        .replaceAll("[\\r\\n]", " ");
                LOG.warn("Email notification {} requires review ({}; code={}; status={}; requestId={}; detail={})",
                        delivery.id(), error.getClass().getSimpleName(), code, sesError.statusCode(), sesError.requestId(), detail);
            } else {
                LOG.warn("Email notification {} requires review ({})", delivery.id(), error.getClass().getSimpleName());
            }
            return new EmailSendResult(EmailSendResult.Outcome.REVIEW, null);
        }
    }

    private Content content(String value) { return Content.builder().charset("UTF-8").data(value).build(); }
}
