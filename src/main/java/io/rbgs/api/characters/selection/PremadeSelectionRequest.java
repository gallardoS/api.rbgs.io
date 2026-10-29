package io.rbgs.api.characters.selection;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record PremadeSelectionRequest(
    @NotNull UUID seasonId, @NotNull @PositiveOrZero Long seasonVersion,
    @PositiveOrZero Long expectedVersion, UUID characterId,
    @Valid SelectionRequest.Declaration declaration, @NotNull UUID companionDeviceId) { }
