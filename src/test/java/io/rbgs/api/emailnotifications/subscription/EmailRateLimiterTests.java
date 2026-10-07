package io.rbgs.api.emailnotifications.subscription;



import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class EmailRateLimiterTests {
    @Test void limitsRepeatedSubjectsButAllowsAnotherClient() {
        var limiter = new EmailRateLimiter(100, 2, 1000, Duration.ofHours(1));
        limiter.check("client-one", "subject");
        limiter.check("client-one", "subject");
        var error = assertThrows(ResponseStatusException.class, () -> limiter.check("client-one", "subject"));
        assertEquals(429, error.getStatusCode().value());
        assertDoesNotThrow(() -> limiter.check("client-two", "subject"));
    }
    @Test void boundsMemoryAndRejectsInvalidLimits() {
        var limiter = new EmailRateLimiter(100, 2, 2, Duration.ofHours(1));
        limiter.check("client", "subject");
        assertThrows(ResponseStatusException.class, () -> limiter.check("other-client", "subject"));
        assertThrows(IllegalArgumentException.class, () -> new EmailRateLimiter(0, 2, 2, Duration.ofHours(1)));
    }
}
