package io.rbgs.api.emailnotifications.delivery;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

@org.springframework.stereotype.Component
@EnableScheduling
public class SesEmailWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SesEmailWorker.class);
    private final EmailNotificationSettings settings;
    private final EmailOutbox outbox;
    private final SesV2Client client;

    public SesEmailWorker(EmailNotificationSettings settings, EmailOutbox outbox, SesV2Client client) {
        this.settings = settings;
        this.outbox = outbox;
        this.client = client;
    }

    @Scheduled(fixedDelayString = "${rbgs.notifications.poll-ms:2000}")
    public void dispatch() {
        if (!settings.enabled()) return;
        EmailOutbox.Delivery delivery = outbox.claim();
        if (delivery == null) return;
        try {
            var response = client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(settings.from())
                    .destination(d -> d.toAddresses(delivery.email()))
                    .emailTags(t -> t.name("outbox-id").value(delivery.id().toString()))
                    .content(c -> c.simple(m -> m.subject(content(delivery.subject()))
                            .body(b -> b.html(content(delivery.html())).text(content(delivery.text())))))
                    .build());
            if (response == null || response.messageId() == null || response.messageId().isBlank()) {
                outbox.failed(delivery, true);
                return;
            }
            outbox.sent(delivery, response.messageId());
        } catch (TooManyRequestsException error) {
            outbox.failed(delivery, false);
        } catch (Exception error) {
            outbox.failed(delivery, true);
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
        }
    }

    private Content content(String value) { return Content.builder().charset("UTF-8").data(value).build(); }

    @Scheduled(fixedDelayString = "${rbgs.notifications.purge-interval:1h}", initialDelayString = "${rbgs.notifications.purge-initial-delay:1m}")
    public void purge() { outbox.purge(); }
}
