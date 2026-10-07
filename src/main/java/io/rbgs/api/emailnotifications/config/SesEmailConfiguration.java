package io.rbgs.api.emailnotifications.config;



import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(EmailNotificationSettings.class)
public class SesEmailConfiguration {
    private final EmailNotificationSettings settings;
    public SesEmailConfiguration(EmailNotificationSettings settings) { this.settings = settings; }
    @Bean(destroyMethod = "close")
    public SesV2Client sesEmailClient(@Value("${AWS_ACCESS_KEY_ID:}") String accessKey,
            @Value("${AWS_SECRET_ACCESS_KEY:}") String secretKey,
            @Value("${AWS_SESSION_TOKEN:}") String sessionToken) {
        var builder = SesV2Client.builder()
                .region(Region.of(settings.region().isBlank() ? "eu-west-1" : settings.region()))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15)).retryPolicy(RetryPolicy.none()));
        if (!accessKey.isBlank() || !secretKey.isBlank()) {
            if (accessKey.isBlank() || secretKey.isBlank()) throw new IllegalArgumentException("Both AWS credential fields are required");
            builder.credentialsProvider(StaticCredentialsProvider.create(sessionToken.isBlank()
                    ? AwsBasicCredentials.create(accessKey, secretKey)
                    : AwsSessionCredentials.create(accessKey, secretKey, sessionToken)));
        }
        return builder.build();
    }
}
