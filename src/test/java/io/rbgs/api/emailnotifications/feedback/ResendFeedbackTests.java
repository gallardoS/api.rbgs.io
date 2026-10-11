package io.rbgs.api.emailnotifications.feedback;

import static org.junit.jupiter.api.Assertions.*;
import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import java.time.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResendFeedbackTests {
    private final byte[] key = "test-webhook-secret-for-signatures".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final ResendFeedbackVerifier verifier = new ResendFeedbackVerifier("whsec_" + Base64.getEncoder().encodeToString(key));
    private final ResendFeedbackParser parser = new ResendFeedbackParser(
            new EmailNotificationSettings("", "rbgs.io <notifications@rbgs.io>", "x".repeat(32),
                    "http://localhost:5173", false, 90, true, "resend", "test-key"), Clock.systemUTC());

    private String signature(String body, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(
                ("msg_test." + timestamp + "." + body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void verifiesRawBodyAndRejectsTamperingAndExpiredSignatures() throws Exception {
        String body = "{\"type\":\"email.delivered\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signed = signature(body, timestamp);
        assertEquals(verifier.verify(body, "msg_test", timestamp, signed), verifier.verify(body, "msg_test", timestamp, signed));
        assertThrows(EmailNotificationException.class, () -> verifier.verify(body + " ", "msg_test", timestamp, signed));
        String old = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
        String oldSignature = signature(body, old);
        assertThrows(EmailNotificationException.class, () -> verifier.verify(body, "msg_test", old, oldSignature));
        assertThrows(EmailNotificationException.class, () -> verifier.verify(body, null, timestamp, signed));
        assertThrows(EmailNotificationException.class, () -> new ResendFeedbackVerifier("").verify(body, "msg_test", timestamp, signed));
    }

    private EmailFeedback parse(String type, String bounce, String from) {
        String body = "{\"type\":\"" + type + "\",\"created_at\":\"" + Instant.now() +
                "\",\"data\":{\"email_id\":\"12345678-1234-1234-1234-123456789012\",\"from\":\"" + from +
                "\",\"to\":[\"Test@example.com\"],\"bounce\":{\"type\":\"" + bounce + "\"}}}";
        return parser.parse(new ObjectMapper().readTree(body));
    }

    @Test void suppressesPermanentBouncesAndComplaintsOnly() {
        assertEquals(EmailSuppressionReason.PERMANENT_BOUNCE, parse("email.bounced", "Permanent", "notifications@rbgs.io").suppressionReason());
        assertEquals(EmailSuppressionReason.COMPLAINT, parse("email.complained", "", "notifications@rbgs.io").suppressionReason());
        assertNull(parse("email.bounced", "Transient", "notifications@rbgs.io").suppressionReason());
        assertNull(parse("email.delivered", "", "notifications@rbgs.io").suppressionReason());
        assertThrows(EmailNotificationException.class, () -> parse("email.bounced", "Permanent", "other@example.com"));
        assertNull(parse("email.sent", "", "notifications@rbgs.io"));
    }
}
