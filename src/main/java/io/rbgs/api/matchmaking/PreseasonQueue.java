package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.PlayCharacterService;
import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.characters.selection.SelectionSettings;
import io.rbgs.api.seasons.SeasonEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PreseasonQueue implements ApplicationRunner {
    public static final String BOT_ISSUER = "https://preseason-bots.invalid";
    public static final UUID SEASON_ID = UUID.fromString("45b0ddbd-7bae-46f3-acb3-56b8f2adfa85");
    static final String RULESET = "PRESEASON_QUEUE_PREVIEW";
    static final String GROUPING_KEY = "PRESEASON_EU";
    static final String COMPLETED = "PRESEASON_PREVIEW_COMPLETE";

    private final boolean enabled;
    private final JdbcTemplate db;
    private final PlayCharacterService characters;

    public PreseasonQueue(@Value("${rbgs.play.preseason-preview:false}") boolean enabled,
            SelectionSettings settings, JdbcTemplate db, PlayCharacterService characters) {
        this.enabled = enabled;
        this.db = db;
        this.characters = characters;
        if (enabled && !SEASON_ID.equals(settings.seasonId())) {
            throw new IllegalStateException("The queue preview requires the configured Preseason");
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!enabled) return;
        db.update("""
                INSERT INTO rbgs.seasons(id, name, kind, rating_subject_type, started_at)
                VALUES (?, 'Preseason', 'PUBLIC', 'ACCOUNT', CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """, SEASON_ID);
    }

    public boolean active(SeasonEntity season) {
        return enabled && season != null && SEASON_ID.equals(season.getId());
    }

    public boolean botActive(UUID account, Instant now) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM rbgs.preseason_bot_run
                    WHERE (expires_at > ? AND (
                        host_ticket_id IS NULL OR EXISTS (
                            SELECT 1 FROM rbgs.queue_tickets host WHERE host.id = host_ticket_id AND host.state = 'QUEUED'
                        )
                    )) OR EXISTS (
                        SELECT 1 FROM rbgs.queue_tickets
                        WHERE account_id = ? AND test_bot AND state = 'PROPOSED'
                    )
                )
                """, Boolean.class, java.sql.Timestamp.from(now), account));
    }

    Map<UUID, String> selectedClasses(Collection<CharacterIdentity> identities) {
        var selected = characters.findAll(identities.stream().map(CharacterIdentity::getAccountId).distinct().toList());
        Map<UUID, String> result = new HashMap<>();
        for (var identity : identities) {
            String characterClass = selectedClass(identity, selected.get(identity.getAccountId()));
            if (characterClass != null) result.put(identity.getId(), characterClass);
        }
        return result;
    }

    private static String selectedClass(CharacterIdentity identity, PlayCharacterService.Selected selected) {
        if (selected == null || !"profile-forever-eu".equals(selected.namespace())
                || !identity.getName().equals(selected.name()) || !identity.getRealm().equals(selected.realm())) return null;
        var playableClass = selected.character() == null ? null : selected.character().playableClass();
        return playableClass == null || playableClass.name() == null || playableClass.name().isBlank()
                ? "UNKNOWN" : playableClass.name();
    }

    static boolean isPreview(SoloQueueMatcher.Proposal proposal) {
        var first = proposal.teamA().getFirst();
        return SEASON_ID.equals(first.seasonId()) && RULESET.equals(first.ruleset());
    }

    static SoloQueueMatcher.GroupingPolicy opposition(SoloQueueGameGateway game) {
        return (ruleset, first, second) -> RULESET.equals(ruleset)
                ? GROUPING_KEY.equals(first) && GROUPING_KEY.equals(second)
                : game.canOppose(ruleset, first, second);
    }
}
