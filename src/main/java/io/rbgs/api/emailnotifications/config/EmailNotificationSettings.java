package io.rbgs.api.emailnotifications.config;



import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rbgs.notifications")
public record EmailNotificationSettings(
        String region,
        String from,
        String tokenSecret,
        String webOrigin,
        boolean seasonLive,
        int dailyLimit,
        boolean sendingEnabled,
        String provider,
        String resendApiKey) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public EmailNotificationSettings {
        provider = java.util.Objects.requireNonNullElse(provider, "ses");
        resendApiKey = java.util.Objects.requireNonNullElse(resendApiKey, "");
        region = java.util.Objects.requireNonNullElse(region, "");
        from = java.util.Objects.requireNonNullElse(from, "");
        tokenSecret = java.util.Objects.requireNonNullElse(tokenSecret, "");
        webOrigin = java.util.Objects.requireNonNullElse(webOrigin, "");
    }
    public EmailNotificationSettings(String region, String from, String tokenSecret, String webOrigin,
            boolean seasonLive, int dailyLimit, boolean sendingEnabled) {
        this(region, from, tokenSecret, webOrigin, seasonLive, dailyLimit, sendingEnabled, "ses", "");
    }

    @Override
    public String toString() {
        return "EmailNotificationSettings[provider=" + provider + ", sendingEnabled=" + sendingEnabled + "]";
    }

    private boolean providerConfigured() {
        return switch (provider) {
            case "ses" -> !region.isBlank();
            case "resend" -> !resendApiKey.isBlank();
            default -> false;
        };
    }

    public boolean enabled() {
        if (!sendingEnabled || !providerConfigured() || from.isBlank() || tokenSecret.length() < 32 || webOrigin.isBlank() || dailyLimit < 1) return false;
        URI origin;
        try { origin = URI.create(webOrigin); } catch (IllegalArgumentException error) { return false; }
        return origin.getHost() != null && ("https".equals(origin.getScheme())
                || ("http".equals(origin.getScheme()) && "localhost".equals(origin.getHost())));
    }
}
