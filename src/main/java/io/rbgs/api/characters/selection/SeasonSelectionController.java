package io.rbgs.api.characters.selection;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class SeasonSelectionController {
    private final SelectionService selections;
    @GetMapping("/api/v1/play/context")
    public ResponseEntity<PlayContext> context(Authentication authentication, CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(selections.context(authentication));
    }
    @PutMapping("/api/v1/me/selection")
    public ResponseEntity<SelectionView> save(Authentication authentication, @Valid @RequestBody SelectionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(selections.save(authentication, request));
    }
}
