package io.rbgs.api.emailnotifications.template;



import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class EmailTemplates {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z]+)\\}\\}");
    private final EmailLinks links;
    private final String layout;
    public EmailTemplates(EmailLinks links) {
        this.links = links;
        try (var input = new ClassPathResource("emails/layout.html").getInputStream()) {
            layout = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("Cannot load email layout", error); }
    }
    public EmailMessage confirmation(String language, String token, String unsubscribeToken) {
        return render("confirmation", language, links.confirmation(language, token), links.unsubscribe(language, unsubscribeToken));
    }
    public EmailMessage launch(String language, String unsubscribeToken) {
        return render("launch", language, links.play(language), links.unsubscribe(language, unsubscribeToken));
    }
    private EmailMessage render(String kind, String language, String actionUrl, String unsubscribeUrl) {
        String locale = "es".equals(language) ? "es" : "en";
        var messages = ResourceBundle.getBundle("emails.messages", Locale.forLanguageTag(locale),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        String heading = messages.getString(kind + ".heading");
        String message = messages.getString(kind + ".message");
        String action = messages.getString(kind + ".action");
        String unsubscribe = messages.getString("unsubscribe");
        var values = Map.of("language", locale, "home", links.home(), "heading", heading, "message", message,
                "action", action, "actionUrl", actionUrl, "unsubscribe", unsubscribe, "unsubscribeUrl", unsubscribeUrl);
        String html = PLACEHOLDER.matcher(layout).replaceAll(match -> {
            String value = values.get(match.group(1));
            if (value == null) throw new IllegalStateException("Unknown email placeholder: " + match.group(1));
            return java.util.regex.Matcher.quoteReplacement(HtmlUtils.htmlEscape(value));
        });
        String text = heading + "\n\n" + message + "\n\n" + action + ": " + actionUrl + "\n\n" + unsubscribe + ": " + unsubscribeUrl;
        return new EmailMessage(messages.getString(kind + ".subject"), html, text);
    }
}
