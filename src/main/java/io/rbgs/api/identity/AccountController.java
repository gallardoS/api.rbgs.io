package io.rbgs.api.identity;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    private final AccountRepository accounts;

    public AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/me")
    public ResponseEntity<Profile> currentUser(Authentication authentication, CsrfToken csrfToken) {
        csrfToken.getToken();
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return ResponseEntity.noContent().build();
        }
        Account account = accounts.findByIdentity(user.getIdToken().getIssuer().toString(), user.getSubject());
        return ResponseEntity.ok(new Profile(account.id(), account.displayName(), account.region(), account.role()));
    }

    public record Profile(UUID id, String displayName, String region, String role) {
    }
}
