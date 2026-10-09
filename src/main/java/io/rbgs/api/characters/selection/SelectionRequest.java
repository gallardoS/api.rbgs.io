package io.rbgs.api.characters.selection;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record SelectionRequest(
    @NotNull UUID seasonId,
    @NotNull @PositiveOrZero Long seasonVersion,
    @PositiveOrZero Long expectedVersion,
    UUID characterId,
    @Valid Declaration declaration,
    @NotNull MatchRole role,
    @NotNull Boolean captainConsent) {
    public record Declaration(
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cntrl}]+$") String name,
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cntrl}]+$") String realm) { }
}
