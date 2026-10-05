package io.rbgs.api.identity;

import io.rbgs.api.identity.dto.Profile;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AccountController {
    private final AccountService accounts;

    @GetMapping("/me")
    public ResponseEntity<Profile> currentUser(Authentication authentication, CsrfToken csrfToken) {
        csrfToken.getToken();
        Profile profile = accounts.currentUser(authentication);
        if (profile == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(profile);
    }
}
