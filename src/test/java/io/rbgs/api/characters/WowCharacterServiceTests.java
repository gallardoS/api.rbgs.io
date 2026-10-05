package io.rbgs.api.characters;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WowCharacterServiceTests {
    @Test
    void fetchesOwnedCharactersWithUserToken() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WowCharacterService(builder);
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-eu&locale=en_GB"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(withSuccess("""
                        {"wow_accounts":[{"id":7,"characters":[{"id":42,"name":"Player",
                        "realm":{"id":1,"name":"Realm","slug":"realm"},"level":80,
                        "playable_class":{"id":1,"name":"Warrior"}}]}]}
                        """, MediaType.APPLICATION_JSON));
        var profile = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(42, profile.wowAccounts().getFirst().characters().getFirst().id());
        assertEquals("Warrior", profile.wowAccounts().getFirst().characters().getFirst().playableClass().name());
        server.verify();
    }

    @Test
    void rejectsMissingConsentAndExpiredTokensBeforeCallingBlizzard() {
        var service = new WowCharacterService(RestClient.builder());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("openid"), Instant.now().plusSeconds(60))))
                .getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().minusSeconds(1))))
                .getStatusCode().value());
    }

    @Test
    void providerFailureDoesNotExposeResponseOrToken() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WowCharacterService(builder);
        server.expect(anything()).andRespond(withServerError().body("sensitive-provider-details"));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))));
        assertEquals(502, error.getStatusCode().value());
        assertFalse(error.getMessage().contains("sensitive-provider-details"));
        server.verify();
    }

    private OAuth2AuthorizedClient client(Set<String> scopes, Instant expiresAt) {
        var registration = ClientRegistration.withRegistrationId("battle-net")
                .clientId("test").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.com/callback").authorizationUri("https://example.com/authorize")
                .tokenUri("https://example.com/token").build();
        return new OAuth2AuthorizedClient(registration, "user", new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "private-token", Instant.now().minusSeconds(120), expiresAt, scopes));
    }
}
