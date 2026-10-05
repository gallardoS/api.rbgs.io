package io.rbgs.api.characters.dto;

import java.util.List;

public record CharacterMedia(List<Asset> assets) {
    public record Asset(String key, String value) { }
}
