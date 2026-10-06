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
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic1x-eu&locale=en_GB"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(withSuccess("""
                        {"wow_accounts":[{"id":7,"characters":[{"id":42,"name":"Player",
                        "realm":{"id":1,"name":"Realm","slug":"realm"},"level":60,
                        "playable_class":{"id":1,"name":"Warrior"}}]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/player/character-media?namespace=profile-classic1x-eu&locale=en_GB"))
                .andRespond(withResourceNotFound());
        expectMissingProfile(server, "profile-classic-eu");
        var profile = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(42, profile.wowAccounts().getFirst().characters().getFirst().id());
        assertEquals("Warrior", profile.wowAccounts().getFirst().characters().getFirst().playableClass().name());
        assertEquals("profile-classic1x-eu", profile.wowAccounts().getFirst().characters().getFirst().namespace());
        server.verify();
    }

    @Test
    void excludesCharactersBelowLevel60BeforeFetchingPortraits() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WowCharacterService(builder);
        server.expect(anything()).andRespond(withSuccess("""
                {"wow_accounts":[{"id":7,"characters":[
                {"id":42,"name":"Player","realm":{"id":1,"slug":"realm"},"level":60},
                {"id":43,"name":"Low","realm":{"id":1,"slug":"realm"},"level":59}]}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/player/character-media?namespace=profile-classic1x-eu&locale=en_GB"))
                .andRespond(withSuccess("""
                        {"assets":[{"key":"avatar","value":"https://render.worldofwarcraft.com/avatar.jpg"},
                        {"key":"inset","value":"https://render.worldofwarcraft.com/inset.jpg"}]}
                        """, MediaType.APPLICATION_JSON));
        expectMissingProfile(server, "profile-classic-eu");
        var profile = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(1, profile.wowAccounts().getFirst().characters().size());
        assertEquals("https://render.worldofwarcraft.com/avatar.jpg",
                profile.wowAccounts().getFirst().characters().getFirst().avatarUrl());
        assertEquals("https://render.worldofwarcraft.com/inset.jpg",
                profile.wowAccounts().getFirst().characters().getFirst().insetUrl());
        server.verify();
    }

    @Test
    void mergesBothClassicBranchesAndKeepsCharactersAboveLevel60WithTheirOwnMedia() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WowCharacterService(builder);
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic1x-eu&locale=en_GB"))
                .andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(withSuccess("""
                        {"wow_accounts":[{"id":7,"characters":[
                        {"id":42,"name":"Player","realm":{"id":1,"slug":"realm"},"level":60}]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/player/character-media?namespace=profile-classic1x-eu&locale=en_GB"))
                .andRespond(withResourceNotFound());
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic-eu&locale=en_GB"))
                .andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(withSuccess("""
                        {"wow_accounts":[{"id":7,"characters":[
                        {"id":42,"name":"Player","realm":{"id":1,"slug":"realm"},"level":81},
                        {"id":43,"name":"Low","realm":{"id":1,"slug":"realm"},"level":53}]},
                        {"id":8,"characters":[
                        {"id":44,"name":"Other","realm":{"id":1,"slug":"realm"},"level":90}]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/player/character-media?namespace=profile-classic-eu&locale=en_GB"))
                .andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(withSuccess("""
                        {"assets":[{"key":"avatar","value":"https://render.worldofwarcraft.com/classic-eu/avatar.jpg"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/other/character-media?namespace=profile-classic-eu&locale=en_GB"))
                .andRespond(withResourceNotFound());
        var profile = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(2, profile.wowAccounts().size());
        var characters = profile.wowAccounts().getFirst().characters();
        assertEquals(2, characters.size());
        assertEquals(60, characters.getFirst().level());
        assertEquals(81, characters.get(1).level());
        assertEquals("profile-classic-eu", characters.get(1).namespace());
        assertEquals("https://render.worldofwarcraft.com/classic-eu/avatar.jpg", characters.get(1).avatarUrl());
        assertEquals(90, profile.wowAccounts().get(1).characters().getFirst().level());
        server.verify();
    }

    @Test
    void missingEraProfileStillQueriesProgression() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WowCharacterService(builder);
        expectMissingProfile(server, "profile-classic1x-eu");
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic-eu&locale=en_GB"))
                .andRespond(withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))).wowAccounts().isEmpty());
        server.verify();
    }

    @Test
    void returnsNotFoundOnlyWhenBothBranchesHaveNoProfile() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WowCharacterService(builder);
        expectMissingProfile(server, "profile-classic1x-eu");
        expectMissingProfile(server, "profile-classic-eu");
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))))
                .getStatusCode().value());
        server.verify();
    }

    @Test
    void progressionFailureIsNotSilentlyReturnedAsACompleteList() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WowCharacterService(builder);
        server.expect(anything()).andRespond(withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withServerError());
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))))
                .getStatusCode().value());
        server.verify();
    }

    private void expectMissingProfile(MockRestServiceServer server, String namespace) {
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=" + namespace + "&locale=en_GB"))
                .andRespond(withResourceNotFound());
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
