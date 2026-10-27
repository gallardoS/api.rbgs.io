package io.rbgs.api.matches;

import static io.rbgs.api.evidence.CaptureJson.*;

import io.rbgs.api.characters.WowCharacterService;
import io.rbgs.api.evidence.CaptureJson;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.benmanes.caffeine.cache.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Temporary moderator test selection; it never changes season or ranked eligibility. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/moderation/capture-tests/anniversary")
public class AnniversaryCaptureTestController {
    private final WowCharacterService characters;
    private final ControlledMatchService controlled;
    private final MatchAvailability availability;
    private final CaptureJson json;
    private final ObjectProvider<OAuth2AuthorizedClientRepository> clients;
    private final Cache<String, AtomicInteger> limits = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(1)).build();

    private OAuth2AuthorizedClient client(Authentication auth, HttpServletRequest request) {
        availability.requireControlledEnabled();
        if (!(auth instanceof OAuth2AuthenticationToken oauth) || !"battle-net".equals(oauth.getAuthorizedClientRegistrationId()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Battle.net again");
        var repository = clients.getIfAvailable();
        return repository == null ? null : repository.loadAuthorizedClient("battle-net", auth, request);
    }

    @GetMapping("/characters")
    public ResponseEntity<?> characters(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(characters.currentAnniversaryTestCharacters(client(auth, request)));
    }

    @PostMapping
    public ResponseEntity<?> create(Authentication auth, HttpServletRequest request) throws IOException {
        if (limits.get(auth.getName(), ignored -> new AtomicInteger()).incrementAndGet() > 10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Retry in one minute");
        if (request.getContentType() == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        byte[] bytes = request.getInputStream().readNBytes(4097);
        if (bytes.length > 4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        var body = json.parse(bytes);
        fields(body, "armMinutes character", "");
        long minutes = number(body.get("armMinutes"));
        if (minutes < 5 || minutes > 120) throw invalid();
        var ref = object(body.get("character"));
        fields(ref, "namespace wowAccountId realmId characterId", "");
        String namespace = text(ref.get("namespace"));
        if (!Set.of("profile-classicann-eu", "profile-classicann-us").contains(namespace)) throw invalid();
        long accountId = number(ref.get("wowAccountId")), realmId = number(ref.get("realmId")), characterId = number(ref.get("characterId"));
        var owned = characters.currentAnniversaryTestCharacters(client(auth, request));
        var matches = new ArrayList<ControlledMatchService.SelectedTestCharacter>();
        for (var account : owned.wowAccounts()) {
            if (account.id() != accountId) continue;
            for (var character : account.characters()) {
                if (character.id() != characterId || character.realm() == null || character.realm().id() != realmId
                        || !namespace.equals(character.namespace())) continue;
                String name = text(character.name());
                String realm = character.realm().name();
                if (realm == null || realm.isBlank()) realm = character.realm().slug();
                matches.add(new ControlledMatchService.SelectedTestCharacter(namespace, accountId, realmId, characterId, name, text(realm)));
            }
        }
        if (matches.size() != 1) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                "This character is no longer available from your Anniversary account. Refresh the list and select again.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(controlled.createAnniversary(auth, minutes, matches.getFirst()));
    }
}
