package io.rbgs.api.characters;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PlayCharacterController {
    private final PlayCharacterService service;
    private final ObjectProvider<OAuth2AuthorizedClientRepository> clients;

    @GetMapping("/api/v1/me/play-character")
    public ResponseEntity<PlayCharacterService.Selected> current(Authentication auth, CsrfToken csrf) {
        csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(auth));
    }

    @PutMapping("/api/v1/me/play-character")
    public ResponseEntity<PlayCharacterService.Selected> save(Authentication auth,
            HttpServletRequest request, @Valid @RequestBody PlayCharacterService.Reference reference) {
        var repository = clients.getIfAvailable();
        OAuth2AuthorizedClient client = repository == null ? null : repository.loadAuthorizedClient("battle-net", auth, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(auth, client, reference));
    }
}
