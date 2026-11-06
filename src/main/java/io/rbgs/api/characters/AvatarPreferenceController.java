package io.rbgs.api.characters;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AvatarPreferenceController {
    private final AvatarPreferenceService service;
    @GetMapping("/api/v1/me/avatar-preference")
    public ResponseEntity<AvatarPreferenceService.Preference> current(Authentication auth, CsrfToken csrf) {
        csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(auth));
    }
    @PutMapping("/api/v1/me/avatar-preference")
    public ResponseEntity<AvatarPreferenceService.Preference> save(Authentication auth,
            @Valid @RequestBody AvatarPreferenceService.Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(auth, request));
    }
}
