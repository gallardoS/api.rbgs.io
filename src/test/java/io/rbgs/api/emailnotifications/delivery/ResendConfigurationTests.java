package io.rbgs.api.emailnotifications.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;
import io.rbgs.api.emailnotifications.config.ResendConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class ResendConfigurationTests {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmailNotificationSettings.class)
    static class SettingsConfiguration {}

    @Test void configurationTextDoesNotExposeCredentials() {
        var settings = new EmailNotificationSettings("", "notifications@rbgs.io", "token-secret-sensitive",
                "http://localhost:5173", false, 90, true, "resend", "api-key-sensitive");
        assertThat(settings.toString()).doesNotContain("token-secret-sensitive", "api-key-sensitive");
    }

    @Test void resendHttpClientIsAbsentWhenSesIsSelected() {
        new ApplicationContextRunner().withUserConfiguration(ResendConfiguration.class)
                .withPropertyValues("rbgs.notifications.provider=ses")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("resendHttpClient"));
    }

    @Test void bindsResendSettingsAndSelectsExactlyOneSender() {
        new ApplicationContextRunner().withUserConfiguration(SettingsConfiguration.class, ResendConfiguration.class,
                ResendEmailSender.class, SesEmailSender.class,
                io.rbgs.api.emailnotifications.feedback.SnsSubscriptionConfirmer.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(io.rbgs.api.emailnotifications.config.EmailFeedbackSettings.class,
                        () -> org.mockito.Mockito.mock(io.rbgs.api.emailnotifications.config.EmailFeedbackSettings.class))
                .withBean("snsSubscriptionHttpClient", java.net.http.HttpClient.class,
                        () -> org.mockito.Mockito.mock(java.net.http.HttpClient.class))
                .withPropertyValues("rbgs.notifications.provider=resend", "rbgs.notifications.resend-api-key=test-key",
                        "rbgs.notifications.from=notifications@rbgs.io", "rbgs.notifications.token-secret=" + "x".repeat(32),
                        "rbgs.notifications.web-origin=http://localhost:5173", "rbgs.notifications.sending-enabled=true",
                        "rbgs.notifications.daily-limit=90")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EmailSender.class);
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(ResendEmailSender.class);
                    assertThat(context.getBean(EmailNotificationSettings.class).enabled()).isTrue();
                });
    }
}
