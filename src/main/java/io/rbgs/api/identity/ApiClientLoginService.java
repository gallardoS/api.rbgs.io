package io.rbgs.api.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Short-lived, single-instance handoff. Browser approval and native polling have distinct secrets. */
@Service
@Profile("local")
public class ApiClientLoginService {
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Ticket> approvals = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, Ticket> polls = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, AtomicInteger> limits = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(1)).build();

    public record Started(String approvalPath, String pollingSecret, int expiresIn, int intervalSeconds) { }
    static final class Ticket {
        Authentication authentication;
        OAuth2AuthorizedClient client;
        boolean consumed;
    }
    public Started start(String address) {
        limit("start:" + address, 5);
        String approval = secret(), poll = secret();
        Ticket ticket = new Ticket();
        approvals.put(approval, ticket);
        polls.put(poll, ticket);
        return new Started("/api/v1/auth/api-client#" + approval, poll, 300, 5);
    }
    public void approve(String secret, Authentication authentication, OAuth2AuthorizedClient client) {
        if (client == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Battle.net again");
        Ticket ticket = lookup(approvals, secret);
        synchronized (ticket) {
            if (ticket.authentication != null || ticket.consumed) throw gone();
            ticket.authentication = authentication;
            ticket.client = client;
            approvals.invalidate(secret);
        }
    }
    Ticket poll(String secret, String address) {
        limit("poll:" + address, 120);
        return lookup(polls, secret);
    }
    void consume(String secret, Ticket ticket) {
        ticket.consumed = true;
        ticket.authentication = null;
        ticket.client = null;
        polls.invalidate(secret);
    }
    private Ticket lookup(Cache<String, Ticket> cache, String secret) {
        Ticket ticket = secret != null && secret.matches("[A-Za-z0-9_-]{43}") ? cache.getIfPresent(secret) : null;
        if (ticket == null) throw gone();
        return ticket;
    }
    private String secret() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private void limit(String key, int max) {
        if (limits.get(key, ignored -> new AtomicInteger()).incrementAndGet() > max)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Retry in one minute");
    }
    static ResponseStatusException gone() { return new ResponseStatusException(HttpStatus.GONE, "Request expired or already used; start again in your API client"); }
}
