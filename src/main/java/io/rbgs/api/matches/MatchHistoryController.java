package io.rbgs.api.matches;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MatchHistoryController {
    private final MatchHistoryService history;
    private final MatchDisputeService disputes;
    public record Read(Long expectedVersion, Long disputeVersion) {}

    @GetMapping("/api/v1/me/matches")
    public ResponseEntity<?> list(Authentication auth, @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor, CsrfToken csrf) {
        var result = history.list(auth, status, limit, cursor); csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/api/v1/me/matches/{id}")
    public ResponseEntity<?> detail(Authentication auth, @PathVariable UUID id, CsrfToken csrf) {
        var result = history.detail(auth, id); csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping("/api/v1/me/matches/{id}/disputes")
    public ResponseEntity<?> open(Authentication auth, @PathVariable UUID id, @RequestBody MatchDisputeService.Write request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(disputes.open(auth, id, request));
    }
    @PostMapping("/api/v1/me/matches/{id}/disputes/replies")
    public ResponseEntity<?> reply(Authentication auth, @PathVariable UUID id, @RequestBody MatchDisputeService.Write request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(disputes.reply(auth, id, request));
    }
    @PostMapping("/api/v1/me/matches/{id}/read")
    public ResponseEntity<?> read(Authentication auth, @PathVariable UUID id, @RequestBody Read request) {
        if (request.expectedVersion() == null || request.expectedVersion() < 0)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide the version you have read");
        disputes.markRead(auth, id, request.expectedVersion(), request.disputeVersion());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/api/v1/moderation/matches/{id}/disputes")
    public ResponseEntity<?> moderation(Authentication auth, @PathVariable UUID id, CsrfToken csrf) {
        var result = disputes.moderation(auth, id); csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping("/api/v1/moderation/matches/{id}/disputes/{dispute}/requests")
    public ResponseEntity<?> request(Authentication auth, @PathVariable UUID id, @PathVariable UUID dispute, @RequestBody MatchDisputeService.Write request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(disputes.requestEvidence(auth, id, dispute, request));
    }
}
