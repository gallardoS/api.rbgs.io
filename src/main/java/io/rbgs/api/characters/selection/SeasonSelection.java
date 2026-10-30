package io.rbgs.api.characters.selection;
import java.util.UUID;
import java.util.Objects;
import io.rbgs.api.seasons.RatingSubjectType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Entity
@Table(name = "season_selections", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonSelection {
    @Id private UUID id;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(name = "season_id", nullable = false) private UUID seasonId;
    @Column(name = "character_id", nullable = false) private UUID characterId;
    @Enumerated(EnumType.STRING)
    @Column(name = "rating_subject_type", nullable = false, length = 16) private RatingSubjectType ratingSubjectType;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_role", nullable = false, length = 16) private MatchRole matchRole = MatchRole.DPS;
    @Column(name = "captain_consent", nullable = false) private boolean captainConsent;
    @Column(name = "companion_device_id") private UUID companionDeviceId;
    @Version @Column(nullable = false) private Long version;

    public SeasonSelection(UUID accountId, UUID seasonId) {
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.seasonId = seasonId;
    }
    public void choose(UUID characterId, RatingSubjectType mode, MatchRole role, boolean captainConsent) {
        this.characterId = characterId;
        this.ratingSubjectType = mode;
        this.matchRole = Objects.requireNonNull(role, "A SoloQ role is required");
        this.captainConsent = captainConsent;
    }
    public void useDevice(UUID deviceId) { this.companionDeviceId = deviceId; }
}
