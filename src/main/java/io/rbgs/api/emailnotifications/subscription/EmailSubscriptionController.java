package io.rbgs.api.emailnotifications.subscription;

import io.rbgs.api.emailnotifications.config.EmailNotificationSettings;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailSubscriptionController {
    private final EmailSubscriptionService service;
    private final EmailNotificationSettings settings;
    private final EmailRateLimiter limiter;

    public EmailSubscriptionController(EmailSubscriptionService service, EmailNotificationSettings settings, EmailRateLimiter limiter) {
        this.service = service;
        this.settings = settings;
        this.limiter = limiter;
    }

    @GetMapping("/api/v1/season-notifications")
    public Map<String, Boolean> status(CsrfToken csrf) {
        csrf.getToken();
        return Map.of("seasonLive", settings.seasonLive(), "subscriptionsAvailable", settings.enabled() && !settings.seasonLive());
    }

    @PostMapping("/api/v1/season-notifications")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody Subscription request, HttpServletRequest servlet) {
        limiter.check(servlet.getRemoteAddr(), request.email().strip().toLowerCase(java.util.Locale.ROOT));
        service.subscribe(request.email().strip(), request.language(), request.website() == null ? "" : request.website());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/season-notifications/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody Link request, HttpServletRequest servlet) {
        limiter.check(servlet.getRemoteAddr(), request.token());
        service.confirm(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/season-notifications/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody Link request, HttpServletRequest servlet) {
        limiter.check(servlet.getRemoteAddr(), request.token());
        service.unsubscribe(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/moderation/season-notifications/launch")
    public Map<String, Integer> launch() { return Map.of("queued", service.launch()); }

    @GetMapping("/api/v1/moderation/season-notifications")
    public Map<String, Boolean> configuration() { return Map.of("configured", settings.enabled(), "seasonLive", settings.seasonLive()); }

    public record Subscription(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "en|es") String language,
            @NotNull @AssertTrue Boolean consent, @Size(max = 200) String website) {}
    public record Link(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
}
