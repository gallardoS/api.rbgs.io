package io.rbgs.api.characters;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
import io.rbgs.api.characters.dto.CharacterMedia;
import io.rbgs.api.characters.dto.CharacterProfile;
import io.rbgs.api.characters.dto.ForeverAccountProfile;
import io.rbgs.api.characters.dto.WowAccountProfile;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WowCharacterLoader {
    private static final List<String> FOREVER_NAMESPACES = List.of("profile-forever-eu", "profile-forever-us");
    private static final List<String> NAMESPACES = List.of(
            "profile-classic1x-eu", "profile-classic-eu", "profile-classic1x-us", "profile-classic-us",
            "profile-classicann-eu", "profile-classicann-us", "profile-forever-eu", "profile-forever-us");
    private record RegionalAccount(String region, long id) { }
    private final RestClient client;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("wow-api-", 0).factory());

    public WowCharacterLoader(RestClient.Builder builder) {
        this.client = builder.build();
    }

    @PreDestroy
    void close() {
        requests.shutdownNow();
    }

    private <T> Future<T> submit(java.util.concurrent.Callable<T> task, long deadline) {
        try {
            return requests.submit(() -> {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Blizzard character loading timed out");
                }
                return task.call();
            });
        } catch (RejectedExecutionException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Character loading is busy");
        }
    }

    private <T> T await(Future<T> task, long deadline) {
        try {
            return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Blizzard character loading timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Character loading interrupted");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(error.getCause());
        }
    }

    @Cacheable(cacheNames = "wowCharacters", key = "#key", sync = true)
    public WowAccountProfile loadCharacters(WowCharacterService.CacheKey key, String token, long deadline) {
        return loadCharacters(token, deadline, FOREVER_NAMESPACES, 1);
    }

    @Cacheable(cacheNames = "wowCharacters", key = "{#key, 'TBC_ANNIVERSARY_TEST'}", sync = true)
    public WowAccountProfile loadAnniversaryTestCharacters(WowCharacterService.CacheKey key, String token, long deadline) {
        return loadCharacters(token, deadline, List.of("profile-classicann-eu", "profile-classicann-us"), 1);
    }

    @Cacheable(cacheNames = "wowCharacters", key = "{#key, 'FOREVER_WSG_TEST'}", sync = true)
    public WowAccountProfile loadForeverTestCharacters(WowCharacterService.CacheKey key, String token, long deadline) {
        return loadCharacters(token, deadline, FOREVER_NAMESPACES, 1);
    }

    @Cacheable(cacheNames = "wowCharacters", key = "{#key, 'PROFILE_REGIONS'}", sync = true)
    public List<String> loadRegions(WowCharacterService.CacheKey key, String token, long deadline) {
        var namespaces = new ArrayList<>(NAMESPACES);
        namespaces.addAll(List.of("profile-eu", "profile-us"));
        return loadCharacters(token, deadline, namespaces, 0, false).wowAccounts().stream()
                .flatMap(account -> account.characters().stream())
                .map(character -> character.namespace().endsWith("-us") ? "NA" : "EU")
                .distinct().sorted().toList();
    }

    private WowAccountProfile loadCharacters(String token, long deadline, List<String> namespaces, int minimumLevel) {
        return loadCharacters(token, deadline, namespaces, minimumLevel, true);
    }

    private WowAccountProfile loadCharacters(String token, long deadline, List<String> namespaces, int minimumLevel, boolean includeDetails) {
        if (System.nanoTime() >= deadline) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Blizzard character loading timed out");
        }
        var tasks = new ArrayList<Future<?>>();
        try {
            var accounts = new LinkedHashMap<RegionalAccount, List<WowAccountProfile.WowCharacter>>();
            boolean foundProfile = false;
            for (String namespace : namespaces) {
                WowAccountProfile profile;
                try {
                    Future<WowAccountProfile> task = submit(() -> fetchProfile(token, namespace), deadline);
                    tasks.add(task);
                    profile = await(task, deadline);
                } catch (RestClientResponseException error) {
                    if (error.getStatusCode().value() == 404) continue;
                    throw error;
                }
                if (profile == null || profile.wowAccounts() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid response from Blizzard");
                }
                foundProfile = true;
                for (var account : profile.wowAccounts()) {
                    if (account.characters() == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid response from Blizzard");
                    }
                    var characters = accounts.computeIfAbsent(new RegionalAccount(region(namespace), account.id()), id -> new ArrayList<>());
                    account.characters().stream().filter(character -> character.level() >= (isForever(namespace) && minimumLevel > 0 ? 1 : minimumLevel))
                            .map(character -> basicCharacter(character, namespace))
                            .forEach(characters::add);
                }
            }
            if (!foundProfile && includeDetails) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No accessible WoW profile in the requested branches and regions");
            }
            accounts.values().forEach(characters -> characters.sort(
                    Comparator.comparingInt(WowAccountProfile.WowCharacter::level).reversed()
                            .thenComparing(WowAccountProfile.WowCharacter::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparingLong(WowAccountProfile.WowCharacter::id)));
            if (!includeDetails) return new WowAccountProfile(accounts.entrySet().stream().map(account ->
                    new WowAccountProfile.WowAccount(account.getKey().id(), List.copyOf(account.getValue()))).toList());
            var details = new LinkedHashMap<WowAccountProfile.WowCharacter, Future<WowAccountProfile.WowCharacter>>();
            for (var characters : accounts.values()) {
                for (var character : characters) {
                    if (System.nanoTime() >= deadline) break;
                    try {
                        var task = submit(() -> withDetails(character, token, character.namespace(), deadline), deadline);
                        tasks.add(task);
                        details.put(character, task);
                    } catch (ResponseStatusException busy) {
                    }
                }
            }
            for (var characters : accounts.values()) {
                for (int i = 0; i < characters.size(); i++) {
                    var task = details.get(characters.get(i));
                    if (task == null) continue;
                    try {
                        characters.set(i, await(task, deadline));
                    } catch (ResponseStatusException error) {
                        if (error.getStatusCode() != HttpStatus.GATEWAY_TIMEOUT) throw error;
                    }
                }
            }
            return new WowAccountProfile(accounts.entrySet().stream().map(account ->
                    new WowAccountProfile.WowAccount(account.getKey().id(), List.copyOf(account.getValue()))).toList());
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Battle.net again");
            }
            if (error.getStatusCode().value() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Blizzard denied access to your WoW profile");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Blizzard is unavailable");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Blizzard is unavailable");
        } finally {
            tasks.forEach(task -> { if (!task.isDone()) task.cancel(true); });
            requests.purge();
        }
    }

    private static boolean isForever(String namespace) {
        return namespace != null && namespace.matches("profile-forever-(eu|us)");
    }

    private WowAccountProfile fetchProfile(String token, String namespace) {
        var response = client.get().uri("https://" + region(namespace)
                        + ".api.blizzard.com/profile/user/wow?namespace=" + namespace + "&locale=" + locale(namespace))
                .headers(headers -> headers.setBearerAuth(token)).retrieve();
        if (!isForever(namespace)) return response.body(WowAccountProfile.class);
        var profile = response.body(ForeverAccountProfile.class);
        if (profile == null || profile.accounts() == null) throw invalidProfile();
        return new WowAccountProfile(profile.accounts().stream().map(account -> {
            if (account.characters() == null) throw invalidProfile();
            return new WowAccountProfile.WowAccount(account.id(), account.characters().stream().map(character -> {
                if (character.id() <= 0 || character.name() == null || character.name().isBlank()
                        || character.surname() == null || character.surname().isBlank()
                        || character.protectedCharacter() == null || character.protectedCharacter().href() == null) throw invalidProfile();
                long realmId = foreverRealmId(character, namespace);
                // Blizzard does not return a realm object or ruleset here. Retain the verified internal ID;
                // do not invent a realm slug or infer a ruleset from its numeric value.
                var realm = new WowAccountProfile.Realm(realmId, "Forever", "");
                return new WowAccountProfile.WowCharacter(character.id(), character.name() + " " + character.surname(), realm,
                        character.playableClass(), character.playableRace(), character.faction(), character.level(),
                        null, null, namespace, null, character.gender());
            }).toList());
        }).toList());
    }

    private long foreverRealmId(ForeverAccountProfile.Character character, String namespace) {
        try {
            var uri = URI.create(character.protectedCharacter().href());
            if (!"https".equals(uri.getScheme()) || !(region(namespace) + ".api.blizzard.com").equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                    || !("namespace=" + namespace).equals(uri.getQuery())) throw invalidProfile();
            var match = java.util.regex.Pattern.compile("/profile/user/wow/protected-character/([1-9][0-9]*)-([1-9][0-9]*)").matcher(uri.getPath());
            if (!match.matches() || Long.parseLong(match.group(2)) != character.id()) throw invalidProfile();
            return Long.parseLong(match.group(1));
        } catch (IllegalArgumentException error) {
            throw invalidProfile();
        }
    }

    private ResponseStatusException invalidProfile() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid response from Blizzard");
    }

    private WowAccountProfile.WowCharacter basicCharacter(WowAccountProfile.WowCharacter character, String namespace) {
        return new WowAccountProfile.WowCharacter(character.id(), character.name(), character.realm(),
                character.playableClass(), character.playableRace(), character.faction(), character.level(),
                character.avatarUrl(), character.insetUrl(), namespace, character.guild(), character.gender(),
                character.avatarSdUrl(), character.avatarHdUrl());
    }

    private static String region(String namespace) {
        return namespace.endsWith("-us") ? "us" : "eu";
    }

    private static String locale(String namespace) {
        return namespace.endsWith("-us") ? "en_US" : "en_GB";
    }

    private WowAccountProfile.WowCharacter withDetails(WowAccountProfile.WowCharacter character, String token, String namespace, long deadline) {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return character;
        String avatar = null;
        String avatarSd = null;
        String inset = null;
        String path = isForever(namespace) ? "/profile/wow/character/{name}" : "/profile/wow/character/{realm}/{name}";
        Object[] identity = isForever(namespace)
                ? new Object[] { character.name().toLowerCase(Locale.ROOT).replace(' ', '-') }
                : new Object[] { character.realm().slug(), character.name().toLowerCase(Locale.ROOT) };
        try {
            CharacterMedia media = client.get().uri(builder -> builder
                    .scheme("https").host(region(namespace) + ".api.blizzard.com")
                    .path(path + "/character-media")
                    .queryParam("namespace", namespace).queryParam("locale", locale(namespace))
                    .build(identity))
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(CharacterMedia.class);
            if (media != null && media.assets() != null) {
                avatar = media.assets().stream().filter(asset -> "avatar".equals(asset.key()))
                        .map(CharacterMedia.Asset::value).filter(this::isBlizzardImage).findFirst().orElse(null);
                if (isForever(namespace)) avatarSd = media.assets().stream().filter(asset -> "avatar-sd".equals(asset.key()))
                        .map(CharacterMedia.Asset::value).filter(this::isBlizzardImage).findFirst().orElse(null);
                inset = media.assets().stream().filter(asset -> "inset".equals(asset.key()))
                        .map(CharacterMedia.Asset::value).filter(this::isBlizzardImage).findFirst().orElse(null);
            }
        } catch (RestClientException | IllegalArgumentException error) {
            // Character media is optional; missing portraits must not hide owned characters.
        }
        var guild = character.guild();
        var gender = character.gender();
        try {
            CharacterProfile profile = System.nanoTime() >= deadline || Thread.currentThread().isInterrupted() ? null : client.get().uri(builder -> builder
                    .scheme("https").host(region(namespace) + ".api.blizzard.com")
                    .path(path)
                    .queryParam("namespace", namespace).queryParam("locale", locale(namespace))
                    .build(identity))
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(CharacterProfile.class);
            if (profile != null) {
                guild = profile.guild();
                if (profile.gender() != null) gender = profile.gender();
            }
        } catch (RestClientException | IllegalArgumentException error) {
            // Guild and gender are optional; unavailable profiles must not hide owned characters.
        }
        return new WowAccountProfile.WowCharacter(character.id(), character.name(), character.realm(),
                character.playableClass(), character.playableRace(), character.faction(), character.level(),
                avatarSd != null ? avatarSd : avatar, inset, namespace, guild, gender, avatarSd, avatar);
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
