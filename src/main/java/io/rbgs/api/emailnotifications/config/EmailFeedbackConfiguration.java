package io.rbgs.api.emailnotifications.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager;
import software.amazon.awssdk.regions.Region;

@Configuration
@EnableConfigurationProperties(EmailFeedbackSettings.class)
public class EmailFeedbackConfiguration {
    @Bean(destroyMethod = "close")
    java.net.http.HttpClient snsSubscriptionHttpClient() {
        return java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    }

    @Bean(destroyMethod = "close")
    SdkHttpClient snsCertificateHttpClient() {
        return UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                .socketTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean(destroyMethod = "close")
    SnsMessageManager snsMessageManager(EmailFeedbackSettings settings, SdkHttpClient snsCertificateHttpClient) {
        if (!settings.topicArn().isBlank() && !settings.configured()) throw new IllegalArgumentException("Invalid SNS feedback topic ARN");
        return SnsMessageManager.builder().region(Region.of(settings.configured() ? settings.region() : "eu-west-1"))
                .httpClient(snsCertificateHttpClient).build();
    }
}
