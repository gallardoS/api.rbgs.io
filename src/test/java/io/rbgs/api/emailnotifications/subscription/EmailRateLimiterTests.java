package io.rbgs.api.emailnotifications.subscription;



import java.time.Duration;
import org.junit.jupiter.api.Test;
import io.rbgs.api.emailnotifications.error.EmailNotificationException;
import io.rbgs.api.emailnotifications.error.EmailNotificationException.Reason;
import static org.junit.jupiter.api.Assertions.*;

class EmailRateLimiterTests {
    @Test void limitsRepeatedSubjectsButAllowsAnotherClient() {
        var limiter = new EmailRateLimiter(100, 2, 1000, Duration.ofHours(1), java.time.Clock.systemUTC());
        limiter.check("client-one", "subject");
        limiter.check("client-one", "subject");
        var error = assertThrows(EmailNotificationException.class, () -> limiter.check("client-one", "subject"));
        assertEquals(Reason.RATE_LIMITED, error.reason());
        assertDoesNotThrow(() -> limiter.check("client-two", "subject"));
    }
    @Test void boundsMemoryAndRejectsInvalidLimits() {
        var limiter = new EmailRateLimiter(100, 2, 2, Duration.ofHours(1), java.time.Clock.systemUTC());
        limiter.check("client", "subject");
        assertThrows(EmailNotificationException.class, () -> limiter.check("other-client", "subject"));
        assertThrows(IllegalArgumentException.class, () -> new EmailRateLimiter(0, 2, 2, Duration.ofHours(1), java.time.Clock.systemUTC()));
    }
    @Test void expiredWindowAllowsAnotherRequestWithoutSleeping() {
        var clock = org.mockito.Mockito.mock(java.time.Clock.class);
        var start = java.time.Instant.parse("2026-10-08T12:00:00Z");
        org.mockito.Mockito.when(clock.instant()).thenReturn(start);
        var limiter = new EmailRateLimiter(100, 1, 1000, Duration.ofHours(1), clock);
        limiter.check("client", "subject");
        assertThrows(EmailNotificationException.class, () -> limiter.check("client", "subject"));
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plus(Duration.ofHours(1)).plusSeconds(1));
        assertDoesNotThrow(() -> limiter.check("client", "subject"));
    }
}
