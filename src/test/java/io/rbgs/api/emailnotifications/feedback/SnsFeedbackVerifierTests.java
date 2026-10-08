package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailFeedbackSettings;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager;
import software.amazon.awssdk.regions.Region;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnsFeedbackVerifierTests {
    private static final String TOPIC = "arn:aws:sns:eu-west-1:123456789012:email-feedback";
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void verifiesRealRsaSignaturesForBothAwsVersionsAndRejectsTampering() throws Exception {
        KeyStore fixture = fixture();
        var http = mock(SdkHttpClient.class);
        var request = mock(ExecutableHttpRequest.class);
        when(http.prepareRequest(any())).thenReturn(request);
        when(request.call()).thenAnswer(invocation -> HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(200).build())
                .responseBody(AbortableInputStream.create(new ByteArrayInputStream(certificatePem(fixture))))
                .build());
        try (var manager = SnsMessageManager.builder().region(Region.EU_WEST_1).httpClient(http).build()) {
            var verifier = new SnsFeedbackVerifier(new EmailFeedbackSettings(TOPIC), json, manager, java.time.Clock.systemUTC());
            for (String version : List.of("1", "2")) {
                Map<String, String> message = notification();
                message.put("SignatureVersion", version);
                if (version.equals("2")) message.put("Subject", "Synthetic SES feedback");
                sign(message, fixture, version.equals("1") ? "SHA1withRSA" : "SHA256withRSA");
                assertEquals("payload", verifier.verify(json.writeValueAsString(message)).message());
                message.put("Message", "tampered");
                assertEquals(Reason.UNTRUSTED, assertThrows(EmailNotificationException.class, () -> verifier.verify(json.writeValueAsString(message))).reason());
            }
        }
    }

    @Test void rejectsUnexpectedTopicAndUnsafeCertificateUrlsBeforeAnyNetworkAccess() {
        var manager = mock(SnsMessageManager.class);
        var verifier = new SnsFeedbackVerifier(new EmailFeedbackSettings(TOPIC), json, manager, java.time.Clock.systemUTC());
        for (String url : List.of("http://sns.eu-west-1.amazonaws.com/SimpleNotificationService-1234567890abcdef1234567890abcdef.pem",
                "https://sns.eu-west-1.amazonaws.com.attacker.test/SimpleNotificationService-1234567890abcdef1234567890abcdef.pem",
                "https://localhost/SimpleNotificationService-1234567890abcdef1234567890abcdef.pem",
                "https://sns.eu-west-1.amazonaws.com:443/SimpleNotificationService-1234567890abcdef1234567890abcdef.pem",
                "https://sns.eu-west-1.amazonaws.com/other.pem",
                "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-1234567890abcdef1234567890abcdef.pem?redirect=1")) {
            var message = notification();
            message.put("SigningCertURL", url);
            assertEquals(Reason.UNTRUSTED, assertThrows(EmailNotificationException.class, () -> verifier.verify(json.writeValueAsString(message))).reason());
        }
        var message = notification();
        message.put("TopicArn", TOPIC + "-other");
        assertThrows(EmailNotificationException.class, () -> verifier.verify(json.writeValueAsString(message)));
        verifyNoInteractions(manager);
    }

    @Test void rejectsStaleMessagesAndDisabledConfiguration() {
        var manager = mock(SnsMessageManager.class);
        var message = notification();
        message.put("Timestamp", Instant.now().minusSeconds(31 * 86400L).toString());
        var verifier = new SnsFeedbackVerifier(new EmailFeedbackSettings(TOPIC), json, manager, java.time.Clock.systemUTC());
        assertThrows(EmailNotificationException.class, () -> verifier.verify(json.writeValueAsString(message)));
        var disabled = new SnsFeedbackVerifier(new EmailFeedbackSettings(""), json, manager, java.time.Clock.systemUTC());
        assertEquals(Reason.UNAVAILABLE, assertThrows(EmailNotificationException.class, () -> disabled.verify("{}")).reason());
        verifyNoInteractions(manager);
    }

    @Test void validatesSubscriptionConfirmationUsingItsSignedTokenAndUrlFields() throws Exception {
        var fixture = fixture();
        var http = mock(SdkHttpClient.class);
        var request = mock(ExecutableHttpRequest.class);
        when(http.prepareRequest(any())).thenReturn(request);
        when(request.call()).thenAnswer(invocation -> HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(200).build())
                .responseBody(AbortableInputStream.create(new ByteArrayInputStream(certificatePem(fixture)))).build());
        try (var manager = SnsMessageManager.builder().region(Region.EU_WEST_1).httpClient(http).build()) {
            var message = notification();
            message.put("Type", "SubscriptionConfirmation");
            message.put("Token", "synthetic-subscription-token");
            message.put("SubscribeURL", "https://sns.eu-west-1.amazonaws.com/?Action=ConfirmSubscription");
            sign(message, fixture, "SHA256withRSA");
            var verifier = new SnsFeedbackVerifier(new EmailFeedbackSettings(TOPIC), json, manager, java.time.Clock.systemUTC());
            assertEquals("SubscriptionConfirmation", verifier.verify(json.writeValueAsString(message)).type().toString());
            message.put("Token", "tampered-token");
            assertThrows(EmailNotificationException.class, () -> verifier.verify(json.writeValueAsString(message)));
        }
    }

    private Map<String, String> notification() {
        var message = new HashMap<String, String>();
        message.put("Type", "Notification");
        message.put("TopicArn", TOPIC);
        message.put("MessageId", UUID.randomUUID().toString());
        message.put("Message", "payload");
        message.put("Timestamp", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now()));
        message.put("SignatureVersion", "2");
        message.put("SigningCertURL", "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-1234567890abcdef1234567890abcdef.pem");
        return message;
    }

    private void sign(Map<String, String> message, KeyStore fixture, String algorithm) throws Exception {
        List<String> fields = message.get("Type").equals("Notification")
                ? List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type")
                : List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");
        var canonical = new StringBuilder();
        for (String field : fields) if (message.containsKey(field)) canonical.append(field).append('\n').append(message.get(field)).append('\n');
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign((PrivateKey) fixture.getKey("sns-test", "test-only-password".toCharArray()));
        signature.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        message.put("Signature", Base64.getEncoder().encodeToString(signature.sign()));
    }

    private byte[] certificatePem(KeyStore store) throws Exception {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(store.getCertificate("sns-test").getEncoded());
        return ("-----BEGIN CERTIFICATE-----\n" + encoded + "\n-----END CERTIFICATE-----\n").getBytes(StandardCharsets.UTF_8);
    }

    private KeyStore fixture() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = getClass().getResourceAsStream("/email-feedback/sns-test.p12")) {
            store.load(input, "test-only-password".toCharArray());
        }
        return store;
    }
}
