package io.rbgs.api.emailnotifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rbgs.notifications.feedback")
public record EmailFeedbackSettings(String topicArn) {
    public EmailFeedbackSettings { topicArn = java.util.Objects.requireNonNullElse(topicArn, "").strip(); }
    public boolean configured() { return topicArn.matches("arn:aws:sns:[a-z]{2}-[a-z]+-\\d:[0-9]{12}:[A-Za-z0-9_-]{1,256}"); }
    public String region() { return topicArn.split(":")[3]; }
    public String accountId() { return topicArn.split(":")[4]; }
}
