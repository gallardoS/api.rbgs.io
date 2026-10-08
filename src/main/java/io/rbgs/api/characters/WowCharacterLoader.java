package io.rbgs.api.characters;

import java.net.URI;
import java.util.ArrayList;
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
    private final RestClient client;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("wow-api-", 0).factory());

    public WowCharacterLoader(RestClient.Builder builder) {
        this.client = builder.baseUrl("https://eu.api.blizzard.com").build();
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
        if (System.nanoTime() >= deadline) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Blizzard character loading timed out");
        }
        var tasks = new ArrayList<Future<?>>();
        try {
            var accounts = new LinkedHashMap<Long, List<WowAccountProfile.WowCharacter>>();
            boolean foundProfile = false;
            for (String namespace : List.of("profile-classic1x-eu", "profile-classic-eu")) {
                WowAccountProfile profile;
                try {
                    Future<WowAccountProfile> task = submit(() -> client.get()
                            .uri("/profile/user/wow?namespace=" + namespace + "&locale=en_GB")
                            .headers(headers -> headers.setBearerAuth(token))
                            .retrieve().body(WowAccountProfile.class), deadline);
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
                    var characters = accounts.computeIfAbsent(account.id(), id -> new ArrayList<>());
                    account.characters().stream().filter(character -> character.level() >= 60)
                            .map(character -> basicCharacter(character, namespace))
                            .forEach(characters::add);
                }
            }
            if (!foundProfile) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No accessible WoW Classic profile in EU");
            }
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
                    new WowAccountProfile.WowAccount(account.getKey(), List.copyOf(account.getValue()))).toList());
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

    private WowAccountProfile.WowCharacter basicCharacter(WowAccountProfile.WowCharacter character, String namespace) {
        return new WowAccountProfile.WowCharacter(character.id(), character.name(), character.realm(),
                character.playableClass(), character.playableRace(), character.faction(), character.level(),
                character.avatarUrl(), character.insetUrl(), namespace, character.guild(), character.gender());
    }

    private WowAccountProfile.WowCharacter withDetails(WowAccountProfile.WowCharacter character, String token, String namespace, long deadline) {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return character;
        String avatar = null;
        String inset = null;
        try {
            CharacterMedia media = client.get().uri(builder -> builder
                    .path("/profile/wow/character/{realm}/{name}/character-media")
                    .queryParam("namespace", namespace).queryParam("locale", "en_GB")
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
        var guild = character.guild();
        var gender = character.gender();
        try {
            CharacterProfile profile = System.nanoTime() >= deadline || Thread.currentThread().isInterrupted() ? null : client.get().uri(builder -> builder
                    .path("/profile/wow/character/{realm}/{name}")
                    .queryParam("namespace", namespace).queryParam("locale", "en_GB")
                    .build(character.realm().slug(), character.name().toLowerCase(Locale.ROOT)))
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(CharacterProfile.class);
            if (profile != null) {
                guild = profile.guild();
                if (profile.gender() != null) gender = profile.gender();
            }
        } catch (RestClientException | IllegalArgumentException error) {
            // Guild and gender are optional; unavailable profiles must not hide owned characters.
        }
        return new WowAccountProfile.WowCharacter(character.id(), character.name(), character.realm(),
                character.playableClass(), character.playableRace(), character.faction(), character.level(), avatar, inset, namespace, guild, gender);
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
