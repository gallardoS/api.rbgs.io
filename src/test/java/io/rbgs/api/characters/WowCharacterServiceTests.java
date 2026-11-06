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
    void profileRegionsIncludeLowLevelClassicAndRetailCharactersAndCacheTheResult() {
        for (var expected : List.of(List.of("EU"), List.of("NA"), List.of("EU", "NA"), List.<String>of())) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
            var service = service(builder);
            for (String namespace : List.of("profile-classic1x-eu", "profile-classic-eu", "profile-classic1x-us",
                    "profile-classic-us", "profile-classicann-eu", "profile-classicann-us", "profile-forever-eu", "profile-forever-us", "profile-eu", "profile-us")) {
                boolean us = namespace.endsWith("-us");
                var request = server.expect(requestTo("https://" + (us ? "us" : "eu")
                        + ".api.blizzard.com/profile/user/wow?namespace=" + namespace + "&locale=" + (us ? "en_US" : "en_GB")))
                        .andExpect(header("Authorization", "Bearer private-token"));
                boolean present = expected.contains(us ? "NA" : "EU")
                        && (namespace.equals("profile-classicann-eu") || namespace.equals("profile-us"));
                if (present) request.andRespond(withSuccess("""
                        {"wow_accounts":[{"id":7,"characters":[{"id":42,"name":"Low",
                        "realm":{"id":1,"slug":"realm"},"level":1}]}]}
                        """, MediaType.APPLICATION_JSON));
                else request.andRespond(withResourceNotFound());
            }
            var client = client(Set.of("wow.profile"), Instant.now().plusSeconds(60));
            assertEquals(expected, service.currentRegions(client));
            assertEquals(expected, service.currentRegions(client));
            server.verify();
        }
    }

    @Test
    void profileRegionsRequireAuthorizationAndNeverReturnPartialRegionsOnProviderFailure() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = service(builder);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.currentRegions(client(Set.of(), Instant.now().plusSeconds(60)))).getStatusCode().value());
        server.expect(requestTo("https://eu.api.blizzard.com/profile/user/wow?namespace=profile-classic1x-eu&locale=en_GB"))
                .andRespond(withServerError());
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> service.currentRegions(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)))).getStatusCode().value());
        server.verify();
    }

    private void expectMissingProfile(MockRestServiceServer server, String namespace) {
        String region = namespace.endsWith("-us") ? "us" : "eu";
        String locale = namespace.endsWith("-us") ? "en_US" : "en_GB";
        server.expect(requestTo("https://" + region + ".api.blizzard.com/profile/user/wow?namespace=" + namespace + "&locale=" + locale))
                .andRespond(withResourceNotFound());
    }

    private void expectCharacterProfile(MockRestServiceServer server, String name, String namespace, String body) {
        String region = namespace.endsWith("-us") ? "us" : "eu";
        String locale = namespace.endsWith("-us") ? "en_US" : "en_GB";
        server.expect(requestTo("https://" + region + ".api.blizzard.com/profile/wow/character/realm/" + name + "?namespace=" + namespace + "&locale=" + locale))
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
                String region = request.getURI().getHost().startsWith("us.") ? "us" : "eu";
                return withSuccess(foreverCharacters(region, 8), MediaType.APPLICATION_JSON).createResponse(request);
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
            assertEquals(8, profile.wowAccounts().getFirst().characters().size());
            assertEquals(2, profile.wowAccounts().size());
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

    @Test
    void anniversaryQueriesItsOwnNamespacesIncludingCharactersBelowPreviewLevel60() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var service = service(builder);
        for (String region : List.of("eu", "us")) {
            String namespace = "profile-classicann-" + region;
            String locale = region.equals("eu") ? "en_GB" : "en_US";
            server.expect(requestTo("https://" + region + ".api.blizzard.com/profile/user/wow?namespace=" + namespace + "&locale=" + locale))
                    .andExpect(header("Authorization", "Bearer private-token"))
                    .andRespond(withSuccess(ownedCharacters(1).replace("\"level\":60", "\"level\":12"), MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://" + region + ".api.blizzard.com/profile/wow/character/realm/player0/character-media?namespace=" + namespace + "&locale=" + locale))
                    .andRespond(withResourceNotFound());
            expectCharacterProfile(server, "player0", namespace, "{}");
        }
        var result = service.currentAnniversaryTestCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(2, result.wowAccounts().size());
        assertEquals("profile-classicann-eu", result.wowAccounts().getFirst().characters().getFirst().namespace());
        assertEquals("profile-classicann-us", result.wowAccounts().getLast().characters().getFirst().namespace());
        assertTrue(result.wowAccounts().stream().allMatch(a -> a.characters().getFirst().level() == 12));
        server.verify();
    }

    @Test
    void anniversaryCacheIsSeparateFromForeverAndStillRequiresCurrentConsent() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var calls = emptyProfiles(server);
        var service = service(builder);
        var auth = client(Set.of("wow.profile"), Instant.now().plusSeconds(60));
        service.currentCharacters(auth);
        var first = service.currentAnniversaryTestCharacters(auth);
        assertEquals(4, calls.get());
        assertSame(first, service.currentAnniversaryTestCharacters(auth));
        assertEquals(4, calls.get());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.currentAnniversaryTestCharacters(
                client(Set.of("wow.profile"), Instant.now().minusSeconds(1)))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.currentAnniversaryTestCharacters(
                client(Set.of("openid"), Instant.now().plusSeconds(60)))).getStatusCode().value());
        assertEquals(4, calls.get());
    }

    @Test
    void anniversaryNeverFallsBackToOtherClassicBranchesAfterProviderFailure() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = service(builder);
        expectMissingProfile(server, "profile-classicann-eu");
        expectMissingProfile(server, "profile-classicann-us");
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.currentAnniversaryTestCharacters(
                client(Set.of("wow.profile"), Instant.now().plusSeconds(60)))).getStatusCode().value());
        server.verify();
    }

    @Test
    void foreverPrefersSdPortraitAndRetainsHdWithValidatedFallback() {
        for (String sd : List.of("https://render.worldofwarcraft.com/forever-eu/avatar-sd.jpg",
                "https://attacker.example/avatar-sd.jpg", "")) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var service = service(builder);
            String hd = "https://render.worldofwarcraft.com/forever-eu/avatar.jpg";
            server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
                var uri = request.getURI();
                if (!uri.getQuery().contains("profile-forever-eu")) return withResourceNotFound().createResponse(request);
                if (uri.getPath().equals("/profile/user/wow"))
                    return withSuccess(foreverProfile("eu"), MediaType.APPLICATION_JSON).createResponse(request);
                if (uri.getPath().endsWith("/character-media")) return withSuccess(
                        "{\"assets\":[{\"key\":\"avatar\",\"value\":\"" + hd + "\"},"
                        + "{\"key\":\"avatar-sd\",\"value\":\"" + sd + "\"}]}",
                        MediaType.APPLICATION_JSON).createResponse(request);
                return withResourceNotFound().createResponse(request);
            });
            var character = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)))
                    .wowAccounts().getFirst().characters().getFirst();
            boolean validSd = sd.startsWith("https://render.worldofwarcraft.com/");
            assertEquals(validSd ? sd : hd, character.avatarUrl());
            assertEquals(validSd ? sd : null, character.avatarSdUrl());
            assertEquals(hd, character.avatarHdUrl());
            // Persisted play/lobby snapshots must retain both variants, including older snapshots without them.
            var mapper = new tools.jackson.databind.ObjectMapper();
            assertEquals(character, mapper.readValue(mapper.writeValueAsString(character), character.getClass()));
            server.verify();
        }
    }

    private String ownedCharacters(int count) {
        var characters = new ArrayList<String>();
        for (int i = 0; i < count; i++) characters.add("{\"id\":" + i + ",\"name\":\"Player" + i
                + "\",\"realm\":{\"id\":1,\"slug\":\"realm\"},\"level\":60}");
        return "{\"wow_accounts\":[{\"id\":7,\"characters\":[" + String.join(",", characters) + "]}]}";
    }

    @Test
    void foreverListsReservedNamesInBothRegionsWithIndependentIdentityAndOptionalDetails() {
        for (var presentRegions : List.of(Set.of("eu"), Set.of("us"), Set.of("eu", "us"))) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var service = service(builder);
            var detailPaths = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            server.expect(ExpectedCount.manyTimes(), anything()).andExpect(header("Authorization", "Bearer private-token"))
                    .andRespond(request -> {
                        var uri = request.getURI();
                        String region = uri.getHost().startsWith("us.") ? "us" : "eu";
                        if (uri.getPath().equals("/profile/user/wow")) {
                            if (!uri.getQuery().contains("profile-forever-") || !presentRegions.contains(region))
                                return withResourceNotFound().createResponse(request);
                            assertTrue(uri.getQuery().contains("locale=" + (region.equals("us") ? "en_US" : "en_GB")));
                            return withSuccess(foreverProfile(region), MediaType.APPLICATION_JSON).createResponse(request);
                        }
                        assertTrue(uri.getPath().equals("/profile/wow/character/sw-sw")
                                || uri.getPath().equals("/profile/wow/character/sw-sw/character-media"));
                        detailPaths.add(region + uri.getPath());
                        return withResourceNotFound().createResponse(request);
                    });
            var auth = client(Set.of("wow.profile"), Instant.now().plusSeconds(60));
            var result = service.currentCharacters(auth);
            assertSame(result, service.currentCharacters(auth));
            assertEquals(presentRegions.size(), result.wowAccounts().size());
            for (var account : result.wowAccounts()) {
                assertEquals(7, account.id());
                var character = account.characters().getFirst();
                assertEquals(1, character.level());
                assertEquals("Sw Sw", character.name());
                assertEquals(6856, character.realm().id());
                assertEquals("Forever", character.realm().name());
                assertEquals("", character.realm().slug());
                assertEquals("FEMALE", character.gender().type());
                assertNull(character.avatarUrl());
                assertEquals(42, character.id());
            }
            assertEquals(presentRegions.stream().map(r -> "profile-forever-" + r).collect(java.util.stream.Collectors.toSet()),
                    result.wowAccounts().stream().flatMap(a -> a.characters().stream()).map(c -> c.namespace()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(presentRegions.size() * 2, detailPaths.size());
            assertEquals(presentRegions.stream().map(r -> r.equals("us") ? "NA" : "EU").sorted().toList(), service.currentRegions(auth));
            server.verify();
        }
    }

    @Test
    void foreverInvalidProtectedIdentityFailsWithoutFollowingProviderLinks() {
        for (String invalid : List.of("https://attacker.example", "https://us.api.blizzard.com",
                "https://eu.api.blizzard.com:444", "https://eu.api.blizzard.com@attacker.example")) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var service = service(builder);
            server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
                assertEquals("/profile/user/wow", request.getURI().getPath());
                return request.getURI().getQuery().contains("profile-forever-eu")
                        ? withSuccess(foreverProfile("eu").replace("https://eu.api.blizzard.com", invalid), MediaType.APPLICATION_JSON).createResponse(request)
                        : withResourceNotFound().createResponse(request);
            });
            assertEquals(502, assertThrows(ResponseStatusException.class, () -> service.currentCharacters(
                    client(Set.of("wow.profile"), Instant.now().plusSeconds(60)))).getStatusCode().value());
            server.verify();
        }
    }

    @Test
    void foreverFailureNeverReturnsAnApparentlyCompleteCatalog() {
        for (var status : List.of(org.springframework.http.HttpStatus.UNAUTHORIZED,
                org.springframework.http.HttpStatus.FORBIDDEN, org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var service = service(builder);
            server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request ->
                    request.getURI().getQuery().contains("profile-forever-us")
                            ? withStatus(status).body("sensitive-provider-details").createResponse(request)
                            : withSuccess("{\"wow_accounts\":[]}", MediaType.APPLICATION_JSON).createResponse(request));
            var error = assertThrows(ResponseStatusException.class, () -> service.currentCharacters(
                    client(Set.of("wow.profile"), Instant.now().plusSeconds(60))));
            assertEquals(status.is5xxServerError() ? 502 : status.value(), error.getStatusCode().value());
            assertFalse(error.getMessage().contains("sensitive-provider-details"));
            server.verify();
        }
    }

    @Test
    void foreverTestQueriesOnlyForeverAndKeepsLowLevelFullNames() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = service(builder);
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            assertTrue(request.getURI().getQuery().contains("profile-forever-"));
            if (!request.getURI().getPath().equals("/profile/user/wow")) return withResourceNotFound().createResponse(request);
            String region = request.getURI().getHost().startsWith("us.") ? "us" : "eu";
            return withSuccess(foreverProfile(region), MediaType.APPLICATION_JSON).createResponse(request);
        });
        var result = service.currentForeverTestCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(2, result.wowAccounts().size());
        assertTrue(result.wowAccounts().stream().allMatch(a -> a.characters().getFirst().level() == 1));
        assertTrue(result.wowAccounts().stream().allMatch(a -> a.characters().getFirst().name().equals("Sw Sw")));
        server.verify();
    }

    @Test
    void playerCatalogQueriesOnlyForeverAndSortsEachRegionalAccountByDescendingLevel() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = service(builder);
        server.expect(ExpectedCount.manyTimes(), anything()).andExpect(header("Authorization", "Bearer private-token"))
                .andRespond(request -> {
                    var uri = request.getURI();
                    assertTrue(uri.getQuery().contains("profile-forever-"), "Player catalog must never query Classic or retail");
                    if (!uri.getPath().equals("/profile/user/wow")) return withResourceNotFound().createResponse(request);
                    String region = uri.getHost().startsWith("us.") ? "us" : "eu";
                    return withSuccess(foreverCharacters(region, 5), MediaType.APPLICATION_JSON).createResponse(request);
                });
        var result = service.currentCharacters(client(Set.of("wow.profile"), Instant.now().plusSeconds(60)));
        assertEquals(2, result.wowAccounts().size());
        for (var account : result.wowAccounts()) {
            assertEquals(List.of(41, 31, 21, 11, 1), account.characters().stream().map(c -> c.level()).toList());
            assertTrue(account.characters().stream().allMatch(c -> c.namespace().startsWith("profile-forever-")));
        }
        assertNotEquals(result.wowAccounts().getFirst().characters().getFirst().namespace(),
                result.wowAccounts().getLast().characters().getFirst().namespace());
        server.verify();
    }

    @Test
    void missingForeverProfilesDoNotFallBackToClassicOrRetail() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = service(builder);
        expectMissingProfile(server, "profile-forever-eu");
        expectMissingProfile(server, "profile-forever-us");
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.currentCharacters(
                client(Set.of("wow.profile"), Instant.now().plusSeconds(60)))).getStatusCode().value());
        server.verify();
    }

    private String foreverCharacters(String region, int count) {
        var characters = new ArrayList<String>();
        for (int i = 0; i < count; i++) characters.add("""
                {"id":%d,"name":"Player%d","surname":"Forever","level":%d,
                "protected_character":{"href":"https://%s.api.blizzard.com/profile/user/wow/protected-character/6856-%d?namespace=profile-forever-%s"}}
                """.formatted(i + 1, i, i * 10 + 1, region, i + 1, region));
        return "{\"wow_accounts\":[{\"id\":7,\"characters\":[" + String.join(",", characters) + "]}]}";
    }

    private String foreverProfile(String region) {
        return """
                {"wow_accounts":[{"id":7,"characters":[{"id":42,"name":"Sw","surname":"Sw","level":1,
                "gender":{"type":"FEMALE","name":"Female"},
                "playable_class":{"id":11,"name":"Druid"},
                "protected_character":{"href":"https://%s.api.blizzard.com/profile/user/wow/protected-character/6856-42?namespace=profile-forever-%s"}}]}]}
                """.formatted(region, region);
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
