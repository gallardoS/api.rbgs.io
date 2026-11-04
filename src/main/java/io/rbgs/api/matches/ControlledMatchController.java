package io.rbgs.api.matches;

import com.github.benmanes.caffeine.cache.*;
import io.rbgs.api.evidence.CaptureJson;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class ControlledMatchController {
    private final ControlledMatchService controlled;
    private final CaptureJson json;
    private final io.rbgs.api.characters.PlayCharacterService playCharacters;
    private final io.rbgs.api.identity.AccountService accounts;
    private final Cache<String, AtomicInteger> limits = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(1)).build();

    @PostMapping({"/api/v1/moderation/capture-tests", "/api/v1/tester/capture-tests"})
    public ResponseEntity<?> create(Authentication auth, HttpServletRequest request) throws IOException {
        if (limits.get(auth.getName(), k -> new AtomicInteger()).incrementAndGet() > 10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Retry in one minute");
        if (request.getContentType() == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        byte[] bytes = request.getInputStream().readNBytes(CaptureJson.MAX_BYTES + 1);
        if (bytes.length > CaptureJson.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Match data is too large to upload (over 256 KiB)");
        var body = json.parse(bytes);
        if (request.getRequestURI().startsWith("/api/v1/tester/")) {
            var selected = playCharacters.current(auth);
            if (selected == null || selected.namespace().startsWith("profile-classicann-"))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Select a compatible character in Play");
            String actor = accounts.currentUser(auth).id().toString();
            for (var item : CaptureJson.rows(body.get("roster"), 1, 40)) {
                var row = CaptureJson.object(item);
                if (actor.equals(row.get("accountId"))) {
                    var character = CaptureJson.object(row.get("character"));
                    if (!selected.name().equals(character.get("name")) || !selected.realm().equals(character.get("realm")))
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Play character changed before confirmation");
                }
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(controlled.create(auth, body));
    }


    @PostMapping("/api/v1/moderation/capture-tests/{id}/cancel")
    public ResponseEntity<Void> cancel(Authentication auth, @PathVariable UUID id, @RequestParam(required = false) Long expectedVersion) {
        controlled.cancel(auth, id, expectedVersion);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
