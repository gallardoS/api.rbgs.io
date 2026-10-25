package io.rbgs.api.matches;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;

@RestController
@RequiredArgsConstructor
public class MatchController {
    private final MatchResultService results;

    @PostMapping("/api/v1/moderation/matches/{id}/decisions")
    public ResponseEntity<MatchResultService.Result> decide(Authentication auth, @PathVariable UUID id,
            @RequestBody MatchResultService.ModeratorDecision decision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.decide(auth, id, decision));
    }

    @GetMapping("/api/v1/moderation/matches")
    public ResponseEntity<MatchResultService.Listing> list(Authentication auth,
            @RequestParam(defaultValue = "ALL") String status, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor, CsrfToken csrf) {
        var listing = results.list(auth, status, limit, cursor);
        csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listing);
    }

    @GetMapping({"/api/v1/matches/{id}/result", "/api/v1/capture-tests/{id}/result"})
    public ResponseEntity<MatchResultService.Result> result(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.result(auth, id));
    }

    @GetMapping({"/api/v1/moderation/matches/{id}/verification", "/api/v1/moderation/capture-tests/{id}/verification"})
    public ResponseEntity<?> review(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.review(auth, id));
    }
}
