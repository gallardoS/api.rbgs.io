package io.rbgs.api.characters;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.client.ExpectedCount;
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
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CachingConfiguration { }

    private WowCharacterService service(RestClient.Builder builder) {
        return service(builder, Duration.ofMinutes(1), Duration.ofSeconds(15));
    }

    private WowCharacterService service(RestClient.Builder builder, Duration ttl, Duration timeout) {
        var context = new AnnotationConfigApplicationContext();
        contexts.add(context);
        context.register(CachingConfiguration.class);
        context.registerBean(CacheManager.class, () -> {
            var manager = new CaffeineCacheManager("wowCharacters");
            manager.setCaffeine(Caffeine.newBuilder().maximumSize(256).expireAfterWrite(ttl));
            return manager;
        });
        context.registerBean(WowCharacterLoader.class, () -> new WowCharacterLoader(builder));
        context.registerBean(WowCharacterService.class,
                () -> new WowCharacterService(context.getBean(WowCharacterLoader.class), timeout));
        context.refresh();
        return context.getBean(WowCharacterService.class);
    }

    @AfterEach
    void closeContexts() { contexts.forEach(AnnotationConfigApplicationContext::close); }

    @Test
    void fetchesOwnedCharactersWithUserToken() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
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
        expectCharacterProfile(server, "player", "profile-classic1x-eu", "{\"guild\":{\"id\":12,\"name\":\"MANDOKIR\"},\"gender\":{\"type\":\"MALE\",\"name\":\"Male\"}}");
        expectMissingProfile(server, "profile-classic-eu");
        var profile = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(42, profile.wowAccounts().getFirst().characters().getFirst().id());
        assertEquals("Warrior", profile.wowAccounts().getFirst().characters().getFirst().playableClass().name());
        assertEquals("profile-classic1x-eu", profile.wowAccounts().getFirst().characters().getFirst().namespace());
        assertEquals("MANDOKIR", profile.wowAccounts().getFirst().characters().getFirst().guild().name());
        assertEquals("MALE", profile.wowAccounts().getFirst().characters().getFirst().gender().type());
        server.verify();
    }

    @Test
    void excludesCharactersBelowLevel60BeforeFetchingPortraits() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic1x-eu&locale=en_GB")).andRespond(withSuccess("""
                {"wow_accounts":[{"id":7,"characters":[
                {"id":42,"name":"Player","realm":{"id":1,"slug":"realm"},"level":60},
                {"id":43,"name":"Low","realm":{"id":1,"slug":"realm"},"level":59}]}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/player/character-media?namespace=profile-classic1x-eu&locale=en_GB"))
                .andRespond(withSuccess("""
                        {"assets":[{"key":"avatar","value":"https://render.worldofwarcraft.com/avatar.jpg"},
                        {"key":"inset","value":"https://render.worldofwarcraft.com/inset.jpg"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/player?namespace=profile-classic1x-eu&locale=en_GB"))
                .andRespond(withResourceNotFound());
        expectMissingProfile(server, "profile-classic-eu");
        var profile = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(1, profile.wowAccounts().getFirst().characters().size());
        assertEquals("https://render.worldofwarcraft.com/avatar.jpg",
                profile.wowAccounts().getFirst().characters().getFirst().avatarUrl());
        assertEquals("https://render.worldofwarcraft.com/inset.jpg",
                profile.wowAccounts().getFirst().characters().getFirst().insetUrl());
        assertNull(profile.wowAccounts().getFirst().characters().getFirst().guild());
        assertNull(profile.wowAccounts().getFirst().characters().getFirst().gender());
        server.verify();
    }

    @Test
    void mergesBothClassicBranchesAndKeepsCharactersAboveLevel60WithTheirOwnMedia() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic1x-eu&locale=en_GB"))
                .andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(withSuccess("""
                        {"wow_accounts":[{"id":7,"characters":[
                        {"id":42,"name":"Player","realm":{"id":1,"slug":"realm"},"level":60}]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/player/character-media?namespace=profile-classic1x-eu&locale=en_GB"))
                .andRespond(withResourceNotFound());
        expectCharacterProfile(server, "player", "profile-classic1x-eu", "{}");
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
        expectCharacterProfile(server, "player", "profile-classic-eu", "{\"guild\":{\"id\":13,\"name\":\"NOGGENFOGGER DODGERS\"},\"gender\":{\"type\":\"FEMALE\",\"name\":\"Female\"}}");
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/other/character-media?namespace=profile-classic-eu&locale=en_GB"))
                .andRespond(withResourceNotFound());
        expectCharacterProfile(server, "other", "profile-classic-eu", "{}");
        var profile = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(2, profile.wowAccounts().size());
        var characters = profile.wowAccounts().getFirst().characters();
        assertEquals(2, characters.size());
        assertEquals(60, characters.getFirst().level());
        assertEquals(81, characters.get(1).level());
        assertEquals("profile-classic-eu", characters.get(1).namespace());
        assertEquals("https://render.worldofwarcraft.com/classic-eu/avatar.jpg", characters.get(1).avatarUrl());
        assertNull(characters.getFirst().guild());
        assertEquals("NOGGENFOGGER DODGERS", characters.get(1).guild().name());
        assertEquals("FEMALE", characters.get(1).gender().type());
        assertEquals(90, profile.wowAccounts().get(1).characters().getFirst().level());
        server.verify();
    }

    @Test
    void missingEraProfileStillQueriesProgression() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
        expectMissingProfile(server, "profile-classic1x-eu");
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic-eu&locale=en_GB"))
                .andRespond(withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))).wowAccounts().isEmpty());
        server.verify();
    }

    @Test
    void returnsNotFoundOnlyWhenBothBranchesHaveNoProfile() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
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
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic1x-eu&locale=en_GB")).andRespond(withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic-eu&locale=en_GB")).andRespond(withServerError());
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))))
                .getStatusCode().value());
        server.verify();
    }

    private void expectMissingProfile(MockRestServiceServer server, String namespace) {
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=" + namespace + "&locale=en_GB"))
                .andRespond(withResourceNotFound());
    }

    private void expectCharacterProfile(MockRestServiceServer server, String name, String namespace, String body) {
        server.expect(requestTo("https://eu.api.blizzard.com/profile/wow/character/realm/" + name + "?namespace=" + namespace + "&locale=en_GB"))
                .andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void rejectsMissingConsentAndExpiredTokensBeforeCallingBlizzard() {
        var service = service(RestClient.builder());
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
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
        server.expect(anything()).andRespond(withServerError().body("sensitive-provider-details"));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))));
        assertEquals(502, error.getStatusCode().value());
        assertFalse(error.getMessage().contains("sensitive-provider-details"));
        server.verify();
    }

    @Test
    void cachesCompletedLoadsButSeparatesAccountsAndRotatedTokens() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var calls = emptyProfiles(server);
        var service = service(builder);
        var auth = client(Set.of("wow.profile"), Instant.now().plusSeconds(60));
        var first = service.currentCharacters(auth);
        assertSame(first, service.currentCharacters(auth));
        assertEquals(2, calls.get());
        var token = auth.getAccessToken();
        service.currentCharacters(new OAuth2AuthorizedClient(auth.getClientRegistration(), "other-account", token));
        assertEquals(4, calls.get());
        var rotated = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "rotated-token",
                token.getIssuedAt(), token.getExpiresAt(), token.getScopes());
        service.currentCharacters(new OAuth2AuthorizedClient(auth.getClientRegistration(), "user", rotated));
        assertEquals(6, calls.get());
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().minusSeconds(1))))
                .getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.currentCharacters(client(Set.of("openid"), Instant.now().plusSeconds(60))))
                .getStatusCode().value());
        assertEquals(6, calls.get());
    }

    @Test
    void refreshesAfterCacheExpiry() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var calls = emptyProfiles(server);
        var service = service(builder, Duration.ZERO, Duration.ofSeconds(2));
        var auth = client(Set.of("wow.profile"), Instant.now().plusSeconds(60));
        service.currentCharacters(auth);
        service.currentCharacters(auth);
        assertEquals(4, calls.get());
    }

    @Test
    void concurrentLoadsForSameAuthorizationShareProviderRequests() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            calls.incrementAndGet();
            started.countDown();
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new java.io.IOException(error); }
            return withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var service = service(builder);
        var auth = client(Set.of("wow.profile"), Instant.now().plusSeconds(60));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.currentCharacters(auth));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> service.currentCharacters(auth));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(50, TimeUnit.MILLISECONDS));
            release.countDown();
            assertSame(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void detailsRunConcurrentlyWithinGlobalLimitAndFallBackAtDeadline() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var calls = new AtomicInteger();
        var fourStarted = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            if (request.getURI().getPath().equals("/profile/user/wow")) {
                return withSuccess(ownedCharacters(8), MediaType.APPLICATION_JSON).createResponse(request);
            }
            calls.incrementAndGet();
            int concurrent = active.incrementAndGet();
            peak.accumulateAndGet(concurrent, Math::max);
            fourStarted.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var service = service(builder, Duration.ofMinutes(1), Duration.ofMillis(800));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            var result = executor.submit(() -> service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60))));
            assertTrue(fourStarted.await(2, TimeUnit.SECONDS));
            var profile = result.get(2, TimeUnit.SECONDS);
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(4, peak.get());
            assertEquals(4, calls.get());
            assertEquals(16, profile.wowAccounts().getFirst().characters().size());
            assertTrue(profile.wowAccounts().getFirst().characters().stream().allMatch(character ->
                    character.avatarUrl() == null && character.namespace() != null));
        } finally { release.countDown(); }
    }

    @Test
    void ownershipTimeoutReturnsGatewayTimeoutAndFailedLoadsCanBeRetried() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var calls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            if (calls.incrementAndGet() == 1) {
                try { new CountDownLatch(1).await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            return withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var service = service(builder, Duration.ofMinutes(1), Duration.ofMillis(200));
        var auth = client(Set.of("wow.profile"), Instant.now().plusSeconds(60));
        assertEquals(504, assertThrows(ResponseStatusException.class, () -> service.currentCharacters(auth))
                .getStatusCode().value());
        assertTrue(service.currentCharacters(auth).wowAccounts().isEmpty());
        assertEquals(3, calls.get());
    }

    private AtomicInteger emptyProfiles(MockRestServiceServer server) {
        var calls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            calls.incrementAndGet();
            return withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        return calls;
    }

    private String ownedCharacters(int count) {
        var characters = new ArrayList<String>();
        for (int i = 0; i < count; i++) characters.add("{\"id\":" + i + ",\"name\":\"Player" + i
                + "\",\"realm\":{\"id\":1,\"slug\":\"realm\"},\"level\":60}");
        return "{\"wow_accounts\":[{\"id\":7,\"characters\":[" + String.join(",", characters) + "]}]}";
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
