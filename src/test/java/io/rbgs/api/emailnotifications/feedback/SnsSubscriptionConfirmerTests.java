package io.rbgs.api.emailnotifications.feedback;

import io.rbgs.api.emailnotifications.config.EmailFeedbackSettings;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import software.amazon.awssdk.messagemanager.sns.model.SnsSubscriptionConfirmation;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnsSubscriptionConfirmerTests {
    private static final String TOPIC = "arn:aws:sns:eu-west-1:123456789012:email-feedback";

    @Test void reconstructsAwsEndpointInsteadOfFollowingTheUrlSuppliedInTheEnvelope() throws Exception {
        var client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any())).thenReturn(response);
        var confirmer = new SnsSubscriptionConfirmer(new EmailFeedbackSettings(TOPIC), client);
        confirmer.confirm(SnsSubscriptionConfirmation.builder().topicArn(TOPIC).token("synthetic+token&value")
                .subscribeUrl(URI.create("https://attacker.test/")).build());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        assertEquals("sns.eu-west-1.amazonaws.com", request.getValue().uri().getHost());
        assertEquals("https", request.getValue().uri().getScheme());
        assertTrue(request.getValue().uri().getRawQuery().contains("Token=synthetic%2Btoken%26value"));
        assertEquals("GET", request.getValue().method());
    }

    @Test void refusesAnUnexpectedTopicWithoutNetworkAccess() {
        var client = mock(HttpClient.class);
        var confirmer = new SnsSubscriptionConfirmer(new EmailFeedbackSettings(TOPIC), client);
        assertThrows(EmailNotificationException.class, () -> confirmer.confirm(SnsSubscriptionConfirmation.builder()
                .topicArn(TOPIC + "-other").token("synthetic-token").build()));
        verifyNoInteractions(client);
    }
}
