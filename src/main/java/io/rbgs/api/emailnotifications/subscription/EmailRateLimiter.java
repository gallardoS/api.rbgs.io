package io.rbgs.api.emailnotifications.subscription;



import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EmailRateLimiter {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int clientLimit;
    private final int subjectLimit;
    private final int maxEntries;
    private final Duration window;
    public EmailRateLimiter(@Value("${rbgs.notifications.rate-limit.client:1000}") int clientLimit,
            @Value("${rbgs.notifications.rate-limit.subject:20}") int subjectLimit,
            @Value("${rbgs.notifications.rate-limit.max-entries:10000}") int maxEntries,
            @Value("${rbgs.notifications.rate-limit.window:1h}") Duration window) {
        if (clientLimit < 1 || subjectLimit < 1 || maxEntries < 1 || window.isNegative() || window.isZero()) throw new IllegalArgumentException("Invalid email rate limits");
        this.clientLimit = clientLimit;
        this.subjectLimit = subjectLimit;
        this.maxEntries = maxEntries;
        this.window = window;
    }
    public synchronized void check(String ip, String subject) {
        Instant now = Instant.now();
        windows.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(now));
        consume(EmailTokens.hash("client:" + ip), clientLimit, now);
        consume(EmailTokens.hash("request:" + ip + ":" + subject), subjectLimit, now);
    }

    private void consume(String key, int limit, Instant now) {
        Window window = windows.get(key);
        if ((window != null && window.count() >= limit) || (window == null && windows.size() >= maxEntries))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again later");
        windows.put(key, window == null ? new Window(now.plus(this.window), 1) : new Window(window.expires(), window.count() + 1));
    }

    private record Window(Instant expires, int count) {}
}
