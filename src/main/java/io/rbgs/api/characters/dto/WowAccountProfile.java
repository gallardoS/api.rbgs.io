package io.rbgs.api.characters.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record WowAccountProfile(@JsonProperty("wow_accounts") List<WowAccount> wowAccounts) {
    public record WowAccount(long id, List<WowCharacter> characters) { }
    public record WowCharacter(long id, String name, Realm realm,
            @JsonProperty("playable_class") NamedReference playableClass,
            @JsonProperty("playable_race") NamedReference playableRace,
            NamedReference faction, int level, String avatarUrl, String insetUrl, String namespace) { }
    public record Realm(long id, String name, String slug) { }
    public record NamedReference(Long id, String name, String type) { }
}
