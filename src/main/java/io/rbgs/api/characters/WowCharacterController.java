package io.rbgs.api.characters;

import io.rbgs.api.characters.dto.WowAccountProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/characters")
public class WowCharacterController {
    private final WowCharacterService characters;

    @GetMapping("/me")
    public WowAccountProfile currentCharacters(
            @RegisteredOAuth2AuthorizedClient("battle-net") OAuth2AuthorizedClient client) {
        return characters.currentCharacters(client);
    }
}
