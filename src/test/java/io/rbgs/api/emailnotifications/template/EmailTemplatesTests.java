package io.rbgs.api.emailnotifications.template;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailTemplatesTests {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "de, Bestätige deine Saisonbenachrichtigung, Die Wettkampfsaison hat begonnen",
        "pt-BR, Confirme seu aviso de temporada, A temporada competitiva começou",
        "ru, Подтвердите уведомление о начале сезона, Соревновательный сезон начался",
        "it, Conferma la notifica di inizio stagione, La stagione competitiva è iniziata"
    })
    void rendersAdditionalLanguagesWithWorkingWebLinks(
            String language, String confirmationSubject, String launchSubject) {
        var templates = templates("https://rbgs.io/");
        var confirmation = templates.confirmation(language, "confirm-token", "unsubscribe-token");
        var launch = templates.launch(language, "unsubscribe-token");
        assertEquals(confirmationSubject, confirmation.subject());
        assertEquals(launchSubject, launch.subject());
        assertTrue(confirmation.text().contains("48"));
        assertTrue(confirmation.text().contains("https://rbgs.io/" + language + "/#season-confirm=confirm-token"));
        assertTrue(launch.text().contains("https://rbgs.io/" + language + "/play"));
        for (var message : new EmailMessage[] {confirmation, launch}) {
            assertTrue(message.html().contains("lang=\"" + language + "\""));
            assertTrue(message.text().contains("https://rbgs.io/" + language + "/#season-unsubscribe=unsubscribe-token"));
            assertFalse(message.html().contains("{{"));
        }
    }

    private EmailTemplates templates(String origin) {
        return new EmailTemplates(new EmailLinks(new EmailNotificationSettings("", "", "", origin, false, 90, false)));
    }
    @Test void rendersAllLanguagesAndTheirLinksWithoutPersistence() {
        var templates = templates("https://rbgs.io/");
        var english = templates.confirmation("en", "confirm-token", "unsubscribe-token");
        var spanish = templates.confirmation("es", "confirm-token", "unsubscribe-token");
        var french = templates.confirmation("fr", "confirm-token", "unsubscribe-token");
        assertEquals("Confirmez votre notification de saison", french.subject());
        assertTrue(french.html().contains("lang=\"fr\""));
        assertTrue(french.text().contains("Bientôt"));
        assertTrue(french.text().contains("48 heures"));
        assertTrue(french.text().contains("https://rbgs.io/fr/#season-confirm=confirm-token"));
        assertTrue(french.text().contains("https://rbgs.io/fr/#season-unsubscribe=unsubscribe-token"));
        var frenchLaunch = templates.launch("fr", "unsubscribe-token");
        assertEquals("La saison compétitive a commencé", frenchLaunch.subject());
        assertTrue(frenchLaunch.text().contains("https://rbgs.io/fr/play"));
        assertFalse(french.html().contains("{{"));
        assertTrue(english.html().contains("Soon"));
        assertTrue(spanish.html().contains("Pronto"));
        assertTrue(spanish.text().contains("https://rbgs.io/es/#season-confirm=confirm-token"));
        assertTrue(english.text().contains("https://rbgs.io/#season-unsubscribe=unsubscribe-token"));
        assertTrue(templates.launch("es", "unsubscribe-token").text().contains("https://rbgs.io/es/play"));
        assertFalse(english.html().contains("{{"));
    }
    @Test void escapesHtmlAndDoesNotInterpretPlaceholderTextFromValues() {
        var message = templates("https://example.invalid/?label={{heading}}&quote=\"").confirmation("en", "token", "unsubscribe");
        assertTrue(message.html().contains("&amp;quote=&quot;"));
        assertTrue(message.html().contains("label={{heading}}"));
        assertTrue(message.text().contains("&quote=\""));
    }
}
