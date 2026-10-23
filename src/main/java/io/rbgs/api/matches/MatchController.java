package io.rbgs.api.matches;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MatchController {
    private final MatchResultService results;

    @GetMapping({"/api/v1/matches/{id}/result", "/api/v1/capture-tests/{id}/result"})
    public ResponseEntity<MatchResultService.Result> result(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.result(auth, id));
    }

    @GetMapping({"/api/v1/moderation/matches/{id}/verification", "/api/v1/moderation/capture-tests/{id}/verification"})
    public ResponseEntity<?> review(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.review(auth, id));
    }
}
