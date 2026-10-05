package io.rbgs.api.characters;

import java.time.Instant;
import java.util.Locale;
import java.net.URI;
import io.rbgs.api.characters.dto.CharacterMedia;
import io.rbgs.api.characters.dto.WowAccountProfile;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WowCharacterService {
    private final RestClient client;

    public WowCharacterService(RestClient.Builder builder) {
        this.client = builder.baseUrl("https://eu.api.blizzard.com").build();
    }

    public WowAccountProfile currentCharacters(OAuth2AuthorizedClient authorizedClient) {
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Battle.net again");
        }
        var token = authorizedClient.getAccessToken();
        if (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Battle.net again");
        }
        if (!token.getScopes().contains("wow.profile")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in again and grant access to your WoW profile");
        }
        try {
            WowAccountProfile profile = client.get()
                    .uri("/profile/user/wow?namespace=profile-classic1x-eu&locale=en_GB")
                    .headers(headers -> headers.setBearerAuth(token.getTokenValue()))
                    .retrieve().body(WowAccountProfile.class);
            if (profile == null || profile.wowAccounts() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid response from Blizzard");
            }
            return new WowAccountProfile(profile.wowAccounts().stream().map(account ->
                    new WowAccountProfile.WowAccount(account.id(), account.characters().stream()
                            .filter(character -> character.level() == 60)
                            .map(character -> withAvatar(character, token.getTokenValue())).toList())).toList());
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Battle.net again");
            }
            if (error.getStatusCode().value() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Blizzard denied access to your WoW profile");
            }
            if (error.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No accessible Classic Era / Season of Discovery profile in EU");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Blizzard is unavailable");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Blizzard is unavailable");
        }
    }

    private WowAccountProfile.WowCharacter withAvatar(WowAccountProfile.WowCharacter character, String token) {
        String avatar = null;
        String inset = null;
        try {
            CharacterMedia media = client.get().uri(builder -> builder
                    .path("/profile/wow/character/{realm}/{name}/character-media")
                    .queryParam("namespace", "profile-classic1x-eu").queryParam("locale", "en_GB")
                    .build(character.realm().slug(), character.name().toLowerCase(Locale.ROOT)))
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(CharacterMedia.class);
            if (media != null && media.assets() != null) {
                avatar = media.assets().stream().filter(asset -> "avatar".equals(asset.key()))
                        .map(CharacterMedia.Asset::value).filter(this::isBlizzardImage).findFirst().orElse(null);
                inset = media.assets().stream().filter(asset -> "inset".equals(asset.key()))
                        .map(CharacterMedia.Asset::value).filter(this::isBlizzardImage).findFirst().orElse(null);
            }
        } catch (RestClientException | IllegalArgumentException error) {
            // Character media is optional; missing portraits must not hide owned characters.
        }
        return new WowAccountProfile.WowCharacter(character.id(), character.name(), character.realm(),
                character.playableClass(), character.playableRace(), character.faction(), character.level(), avatar, inset);
    }

    private boolean isBlizzardImage(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && uri.getUserInfo() == null
                    && (host.endsWith(".worldofwarcraft.com") || host.endsWith(".blizzard.com")
                    || host.endsWith(".blizzardstatic.com") || host.endsWith(".battle.net"));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
