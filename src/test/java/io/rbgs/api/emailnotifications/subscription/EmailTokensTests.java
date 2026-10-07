package io.rbgs.api.emailnotifications.subscription;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailTokensTests {
    @Test void confirmationIsRandomAndUnsubscribeIsStablePerSubscription() {
        var tokens = new EmailTokens(new EmailNotificationSettings("", "", "a".repeat(32), "", false, 90, false));
        String confirmation = tokens.confirmation();
        assertTrue(confirmation.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(confirmation, tokens.confirmation());
        UUID id = UUID.randomUUID();
        assertEquals(tokens.unsubscribe(id), tokens.unsubscribe(id));
        assertNotEquals(tokens.unsubscribe(id), tokens.unsubscribe(UUID.randomUUID()));
        assertEquals(64, EmailTokens.hash(confirmation).length());
    }
}
