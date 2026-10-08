package io.rbgs.api.emailnotifications.template;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;

import org.springframework.stereotype.Component;

@Component
public class EmailLinks {
    private final EmailNotificationSettings settings;
    public EmailLinks(EmailNotificationSettings settings) { this.settings = settings; }
    public String home() { return settings.webOrigin().replaceAll("/+$", ""); }
    public String confirmation(String language, String token) { return localized(language) + "/#season-confirm=" + token; }
    public String unsubscribe(String language, String token) { return localized(language) + "/#season-unsubscribe=" + token; }
    public String play(String language) { return localized(language) + "/play"; }
    private String localized(String language) { return home() + switch (language) { case "es", "fr" -> "/" + language; default -> ""; }; }
}
