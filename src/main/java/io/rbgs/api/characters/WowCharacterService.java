package io.rbgs.api.characters;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import io.rbgs.api.characters.dto.WowAccountProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WowCharacterService {
    private final WowCharacterLoader loader;
    private final Duration loadTimeout;

    public record CacheKey(String registration, String principal, String tokenDigest) { }

    @Autowired
    public WowCharacterService(WowCharacterLoader loader) {
        this(loader, Duration.ofSeconds(15));
    }

    WowCharacterService(WowCharacterLoader loader, Duration loadTimeout) {
        this.loader = loader;
        this.loadTimeout = loadTimeout;
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
        CacheKey key = new CacheKey(authorizedClient.getClientRegistration().getRegistrationId(),
                authorizedClient.getPrincipalName(), digest(token.getTokenValue()));
        return loader.loadCharacters(key, token.getTokenValue(), System.nanoTime() + loadTimeout.toNanos());
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

}
