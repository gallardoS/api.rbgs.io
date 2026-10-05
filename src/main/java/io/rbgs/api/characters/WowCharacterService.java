package io.rbgs.api.characters;

import java.time.Instant;
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
                    .uri("/profile/user/wow?namespace=profile-eu&locale=en_GB")
                    .headers(headers -> headers.setBearerAuth(token.getTokenValue()))
                    .retrieve().body(WowAccountProfile.class);
            if (profile == null || profile.wowAccounts() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid response from Blizzard");
            }
            return profile;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Battle.net again");
            }
            if (error.getStatusCode().value() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Blizzard denied access to your WoW profile");
            }
            if (error.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No accessible WoW profile in EU");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Blizzard is unavailable");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Blizzard is unavailable");
        }
    }
}
