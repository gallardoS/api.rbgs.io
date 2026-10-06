package io.rbgs.api.characters.dto;

public record CharacterProfile(WowAccountProfile.NamedReference guild, WowAccountProfile.NamedReference gender) { }
