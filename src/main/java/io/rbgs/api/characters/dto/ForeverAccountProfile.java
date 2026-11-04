package io.rbgs.api.characters.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import io.rbgs.api.characters.dto.WowAccountProfile.NamedReference;

/** Provider shape: Forever has two-part names and no realm object. */
public record ForeverAccountProfile(@JsonProperty("wow_accounts") List<Account> accounts) {
    public record Account(long id, List<Character> characters) { }
    public record Link(String href) { }
    public record Character(long id, String name, String surname,
            @JsonProperty("protected_character") Link protectedCharacter,
            @JsonProperty("playable_class") NamedReference playableClass,
            @JsonProperty("playable_race") NamedReference playableRace,
            NamedReference faction, NamedReference gender, int level) { }
}
