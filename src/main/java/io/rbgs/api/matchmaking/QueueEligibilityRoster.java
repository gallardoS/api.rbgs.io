package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.characters.selection.CharacterIdentityRepository;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import io.rbgs.api.devices.DeviceEntity;
import io.rbgs.api.devices.DeviceRepository;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.entity.AccountEntity;
import io.rbgs.api.matches.MatchAssignmentRepository;
import io.rbgs.api.seasons.RatingSubjectType;
import io.rbgs.api.seasons.SeasonEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Loads current eligibility inputs for one roster within the caller's transaction. */
@Component
@RequiredArgsConstructor
class QueueEligibilityRoster {
    private final AccountRepository accounts;
    private final SeasonSelectionRepository selections;
    private final CharacterIdentityRepository characters;
    private final DeviceRepository devices;
    private final MatchAssignmentRepository assignments;

    record Player(AccountEntity activeAccount, SeasonSelection selection, CharacterIdentity character,
                  DeviceEntity device, boolean blockingMatch) {
        UUID subject(SeasonEntity season) {
            return season.getRatingSubjectType() == RatingSubjectType.ACCOUNT
                    ? selection.getAccountId() : character.getId();
        }
    }

    void lockAccounts(Collection<UUID> ids) {
        Set<UUID> unique = Set.copyOf(ids);
        if (!unique.isEmpty() && accounts.lockQueueAccounts(unique).size() != unique.size())
            throw new java.util.NoSuchElementException("Queue participant account is missing");
    }

    Map<UUID, Player> load(Collection<UUID> ids, SeasonEntity season, Instant now) {
        if (ids.isEmpty()) return Map.of();
        var active = index(accounts.findAllById(ids).stream().filter(a -> "ACTIVE".equals(a.getStatus())).toList(), AccountEntity::getId);
        var blocking = Set.copyOf(assignments.blockingParticipants(ids, now));
        var selected = season == null ? List.<SeasonSelection>of() : selections.findBySeasonIdAndAccountIdIn(season.getId(), ids);
        var byAccount = index(selected, SeasonSelection::getAccountId);
        var byCharacter = index(characters.findAllById(ids(selected, SeasonSelection::getCharacterId)), CharacterIdentity::getId);
        var byDevice = index(devices.findAllById(ids(selected, SeasonSelection::getCompanionDeviceId)), DeviceEntity::getId);

        Map<UUID, Player> roster = new HashMap<>();
        for (UUID account : ids) {
            var selection = byAccount.get(account);
            roster.put(account, new Player(active.get(account), selection,
                    selection == null ? null : byCharacter.get(selection.getCharacterId()),
                    selection == null ? null : byDevice.get(selection.getCompanionDeviceId()), blocking.contains(account)));
        }
        return roster;
    }

    static Set<UUID> subjects(Map<UUID, Player> roster, SeasonEntity season) {
        return roster.values().stream().filter(p -> p.character() != null)
                .map(p -> p.subject(season)).collect(Collectors.toSet());
    }

    static List<CharacterIdentity> characters(Map<UUID, Player> roster) {
        return roster.values().stream().map(Player::character).filter(java.util.Objects::nonNull).toList();
    }

    private static <T> Map<UUID, T> index(List<T> rows, Function<T, UUID> key) {
        return rows.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static Set<UUID> ids(List<SeasonSelection> rows, Function<SeasonSelection, UUID> key) {
        return rows.stream().map(key).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }
}
