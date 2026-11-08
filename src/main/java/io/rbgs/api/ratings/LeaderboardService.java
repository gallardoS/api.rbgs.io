package io.rbgs.api.ratings;

import io.rbgs.api.matchmaking.ConfirmedRatingStore.Queue;
import io.rbgs.api.seasons.RatingSubjectType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LeaderboardService {
    static final int MAX_OFFSET = 10_000;
    private static final String RATED_MATCHES = """
            WITH rated_matches AS (
                SELECT c.match_id, c.season_id, c.queue_type, c.rating_order, result.result_version, result.outcome,
                       coalesce(m.competition_ended_at, m.created_at) AS played_at
                FROM rbgs.match_competitive_context c
                JOIN rbgs.matches m ON m.id = c.match_id
                CROSS JOIN LATERAL (
                    SELECT result_version, outcome FROM rbgs.rating_result_revisions
                    WHERE match_id = c.match_id ORDER BY result_version DESC LIMIT 1
                ) result
                WHERE c.season_id = ?
                  AND m.cancelled_at IS NULL AND m.verification_status IN ('VERIFIED', 'RESOLVED')
                  AND result.outcome = CASE m.outcome_kind WHEN 'DRAW' THEN 'DRAW' WHEN 'WIN' THEN m.winning_team END
            )
            """;
    private static final String ASSIGNMENT_JOINS = """
            FROM rated_matches rated
            JOIN rbgs.rating_movements movement ON movement.replay_match_id = rated.match_id
                AND movement.match_id = rated.match_id AND movement.replay_result_version = rated.result_version
            JOIN rbgs.match_assignments a ON a.match_id = rated.match_id AND a.assigned_team = movement.team
                AND movement.subject_id = CASE WHEN ? = 'ACCOUNT' THEN a.account_id ELSE a.character_id END
            """;
    private static final String APPEARANCE_COLUMNS = """
            participant.character_class,
            play.character_profile #>> '{playable_race,name}' AS race,
            play.character_profile #>> '{gender,type}' AS gender
            """;
    private static final String APPEARANCE_JOINS = """
            LEFT JOIN LATERAL (
                SELECT player ->> 'characterClass' AS character_class
                FROM jsonb_array_elements((context.snapshot::jsonb #> '{proposal,teamA}')
                    || (context.snapshot::jsonb #> '{proposal,teamB}')) AS player
                WHERE player ->> 'characterId' = ch.id::text AND player ->> 'accountId' = ch.account_id::text
                LIMIT 1
            ) participant ON true
            LEFT JOIN rbgs.play_characters play ON play.account_id = ch.account_id AND ch.product = 'WOW_FOREVER'
                AND play.region = CASE WHEN ch.region = 'US' THEN 'NA' ELSE ch.region END
                AND play.namespace LIKE 'profile-forever-%'
                AND CASE WHEN ch.source = 'BLIZZARD_VERIFIED' THEN
                    play.namespace = ch.provider_namespace AND play.realm_id::text = ch.provider_realm_id
                    AND play.character_id::text = ch.provider_character_id
                ELSE lower(play.name) = lower(ch.name) AND lower(play.realm) = lower(ch.realm) END
            """;
    private static final int PROFILE_HISTORY_LIMIT = 20;
    private final JdbcTemplate db;

    public record Season(UUID id, String name, RatingSubjectType subjectType) {}
    public record Entry(long rank, UUID profileRef, String name, String realm, int rating, int wins, int losses, int draws,
                        String characterClass, String race, String gender, String region, boolean uniqueName) {}
    public record Page(List<Season> seasons, UUID seasonId, List<Entry> entries, Integer nextOffset, int totalPages) {}
    public record RatingPoint(Instant playedAt, int rating) {}
    public record QueueRating(int rating, int games, long wins, long losses, long draws, int peak,
                              int initialRating, List<RatingPoint> progression) {}
    public record Appearance(String characterClass, String race, String gender, String region) {}
    public record ProfileMatch(Queue queue, String character, String realm, Instant playedAt, String outcome, int rating) {}
    public record Profile(Season season, String name, String realm, Appearance appearance,
                          QueueRating solo, QueueRating premade, List<ProfileMatch> matches) {}
    private record Participation(ProfileMatch match, UUID matchId, UUID characterId, long ratingOrder,
                                 int rating, int before) {}
    private record ProfileIdentity(UUID characterId, UUID accountId) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(Queue queue, UUID seasonId, String search, int limit, int offset) {
        var seasons = db.query("""
                SELECT id, name, rating_subject_type FROM rbgs.seasons
                WHERE started_at <= CURRENT_TIMESTAMP
                ORDER BY started_at DESC, id ASC LIMIT 50
                """, (row, index) -> new Season(row.getObject("id", UUID.class), row.getString("name"),
                RatingSubjectType.valueOf(row.getString("rating_subject_type"))));
        if (seasonId == null && seasons.isEmpty()) return new Page(seasons, null, List.of(), null, 0);
        var season = seasonId == null ? seasons.getFirst() : seasons.stream().filter(item -> item.id().equals(seasonId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season unavailable"));
        return db.query(RATED_MATCHES + """
                , stats AS (
                    SELECT movement.subject_id,
                           count(*) FILTER (WHERE rated.outcome = movement.team) AS wins,
                           count(*) FILTER (WHERE rated.outcome IN ('A', 'B') AND rated.outcome <> movement.team) AS losses,
                           count(*) FILTER (WHERE rated.outcome = 'DRAW') AS draws,
                           (array_agg(a.character_id ORDER BY rated.rating_order DESC))[1] AS character_id,
                           (array_agg(rated.match_id ORDER BY rated.rating_order DESC))[1] AS match_id
                """ + ASSIGNMENT_JOINS + """
                    WHERE rated.queue_type = ?
                    GROUP BY movement.subject_id
                ), standings AS (
                    SELECT row_number() OVER (ORDER BY rating.visible_rating DESC, rating.subject_id) AS rank,
                           ch.id AS profile_ref, ch.name, ch.realm, stats.match_id, rating.visible_rating,
                           stats.wins, stats.losses, stats.draws
                    FROM rbgs.competitive_ratings rating
                    JOIN rbgs.accounts account ON account.id = rating.account_id AND account.account_status = 'ACTIVE'
                    JOIN stats ON stats.subject_id = rating.subject_id
                    JOIN rbgs.character_identities ch ON ch.id = stats.character_id
                        AND ch.account_id = rating.account_id
                    WHERE rating.season_id = ? AND rating.queue_type = ? AND rating.subject_type = ?
                      AND rating.games_played > 0
                ), filtered AS (
                    SELECT * FROM standings
                    WHERE strpos(lower(name || ' ' || realm), lower(?)) > 0 OR ? = ''
                ), page AS (
                    SELECT * FROM filtered
                    ORDER BY rank LIMIT ? OFFSET ?
                )
                SELECT page.*, totals.entry_count, ch.region,
                       NOT EXISTS (
                           SELECT 1 FROM rbgs.character_identities other
                           JOIN rbgs.accounts owner ON owner.id = other.account_id AND owner.account_status = 'ACTIVE'
                           WHERE other.id <> ch.id AND lower(other.region) = lower(ch.region)
                             AND lower(other.realm) = lower(ch.realm) AND lower(other.name) = lower(ch.name)
                       ) AS unique_name,
                """ + APPEARANCE_COLUMNS + """
                FROM (SELECT count(*) AS entry_count FROM filtered) totals
                LEFT JOIN page ON true
                LEFT JOIN rbgs.character_identities ch ON ch.id = page.profile_ref
                LEFT JOIN rbgs.match_competitive_context context ON context.match_id = page.match_id
                """ + APPEARANCE_JOINS + """
                ORDER BY page.rank
                """, rows -> {
                    rows.next();
                    long count = rows.getLong("entry_count");
                    int totalPages = (int) Math.min((count + limit - 1) / limit, MAX_OFFSET / limit + 1);
                    int next = offset + limit;
                    var entries = new ArrayList<Entry>();
                    do {
                        UUID reference = rows.getObject("profile_ref", UUID.class);
                        if (reference == null) break;
                        entries.add(new Entry(rows.getLong("rank"), reference, rows.getString("name"), rows.getString("realm"),
                                rows.getInt("visible_rating"), rows.getInt("wins"), rows.getInt("losses"), rows.getInt("draws"),
                                rows.getString("character_class"), rows.getString("race"), rows.getString("gender"),
                                rows.getString("region"), rows.getBoolean("unique_name")));
                    } while (rows.next());
                    return new Page(seasons, season.id(), entries, next < count && next <= MAX_OFFSET ? next : null, totalPages);
                },
                season.id(), season.subjectType().name(), queue.name(), season.id(), queue.name(),
                season.subjectType().name(), search, search, limit, offset);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Profile profile(UUID seasonId, UUID characterId) {
        var owner = db.query("""
                SELECT ch.account_id FROM rbgs.character_identities ch
                JOIN rbgs.accounts account ON account.id = ch.account_id AND account.account_status = 'ACTIVE'
                WHERE ch.id = ?
                """, (row, index) -> row.getObject("account_id", UUID.class), characterId).stream().findFirst()
                .orElseThrow(LeaderboardService::profileUnavailable);
        return profile(seasonId, new ProfileIdentity(characterId, owner));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Profile namedProfile(UUID seasonId, String region, String realm, String name) {
        var identities = db.query("""
                SELECT ch.id, ch.account_id FROM rbgs.character_identities ch
                JOIN rbgs.accounts account ON account.id = ch.account_id AND account.account_status = 'ACTIVE'
                WHERE lower(ch.region) = lower(?) AND lower(ch.realm) = lower(?) AND lower(ch.name) = lower(?)
                LIMIT 2
                """, (row, index) -> new ProfileIdentity(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class)),
                region, realm, name);
        if (identities.size() != 1) throw profileUnavailable();
        return profile(seasonId, identities.getFirst());
    }

    private Profile profile(UUID seasonId, ProfileIdentity identity) {
        var season = db.query("""
                SELECT season.id, season.name, season.rating_subject_type FROM rbgs.seasons season
                WHERE season.started_at <= CURRENT_TIMESTAMP
                  AND (season.id = ? OR (?::uuid IS NULL AND EXISTS (
                      SELECT 1 FROM rbgs.competitive_ratings rating
                      WHERE rating.season_id = season.id AND rating.subject_type = season.rating_subject_type
                        AND rating.account_id = ? AND rating.games_played > 0
                        AND rating.subject_id = CASE season.rating_subject_type WHEN 'ACCOUNT' THEN ? ELSE ? END
                  )))
                ORDER BY season.started_at DESC, season.id ASC LIMIT 1
                """, (row, index) -> new Season(row.getObject("id", UUID.class), row.getString("name"),
                RatingSubjectType.valueOf(row.getString("rating_subject_type"))),
                seasonId, seasonId, identity.accountId(), identity.accountId(), identity.characterId()).stream().findFirst()
                .orElseThrow(LeaderboardService::profileUnavailable);
        var type = season.subjectType();
        UUID subject = type == RatingSubjectType.ACCOUNT ? identity.accountId() : identity.characterId();
        var history = db.query(RATED_MATCHES + """
                , latest_movements AS (
                    SELECT DISTINCT ON (movement.match_id) movement.match_id, movement.visible_before, movement.visible_after
                    FROM rbgs.rating_movements movement
                    JOIN rated_matches rated ON rated.match_id = movement.match_id
                    JOIN rbgs.rating_result_revisions replay ON replay.match_id = movement.replay_match_id
                        AND replay.result_version = movement.replay_result_version
                    WHERE movement.subject_id = ?
                    ORDER BY movement.match_id, replay.replay_number DESC
                )
                SELECT rated.queue_type, rated.match_id, rated.played_at, rated.rating_order, ch.id AS character_id,
                       ch.name, ch.realm, rating.visible_rating, latest.visible_before, latest.visible_after,
                       CASE WHEN rated.outcome = 'DRAW' THEN 'DRAW'
                            WHEN rated.outcome = movement.team THEN 'WIN' ELSE 'LOSS' END AS outcome
                """ + ASSIGNMENT_JOINS + """
                    JOIN rbgs.character_identities ch ON ch.id = a.character_id AND ch.account_id = a.account_id
                    JOIN rbgs.competitive_ratings rating ON rating.season_id = rated.season_id
                        AND rating.queue_type = rated.queue_type AND rating.subject_type = ?
                        AND rating.subject_id = movement.subject_id AND rating.account_id = a.account_id
                    JOIN latest_movements latest ON latest.match_id = rated.match_id
                WHERE movement.subject_id = ? AND a.account_id = ? AND rating.games_played > 0
                ORDER BY rated.played_at DESC, rated.match_id
                """, (row, index) -> new Participation(new ProfileMatch(Queue.valueOf(row.getString("queue_type")),
                row.getString("name"), row.getString("realm"), row.getTimestamp("played_at").toInstant(),
                row.getString("outcome"), row.getInt("visible_after")),
                row.getObject("match_id", UUID.class), row.getObject("character_id", UUID.class), row.getLong("rating_order"),
                row.getInt("visible_rating"), row.getInt("visible_before")),
                season.id(), subject, type.name(), type.name(), subject, identity.accountId());
        if (history.isEmpty()) throw profileUnavailable();
        var newest = history.getFirst();
        var appearance = db.queryForObject("SELECT ch.region, " + APPEARANCE_COLUMNS + """
                FROM rbgs.character_identities ch
                JOIN rbgs.match_competitive_context context ON context.match_id = ?
                """ + APPEARANCE_JOINS + "WHERE ch.id = ?",
                (row, index) -> new Appearance(row.getString("character_class"), row.getString("race"),
                        row.getString("gender"), row.getString("region")),
                newest.matchId(), newest.characterId());
        var recent = new ArrayList<ProfileMatch>();
        var counts = new EnumMap<Queue, Integer>(Queue.class);
        for (var item : history) {
            if (counts.merge(item.match().queue(), 1, Integer::sum) <= PROFILE_HISTORY_LIMIT) recent.add(item.match());
        }
        return new Profile(season, newest.match().character(), newest.match().realm(), appearance,
                rating(history, Queue.SOLO), rating(history, Queue.PREMADE), recent);
    }

    private static QueueRating rating(List<Participation> history, Queue queue) {
        var games = history.stream().filter(item -> item.match().queue() == queue)
                .sorted(Comparator.comparingLong(Participation::ratingOrder)).toList();
        if (games.isEmpty()) return null;
        var progression = games.stream().map(item -> new RatingPoint(item.match().playedAt(), item.match().rating())).toList();
        int initial = games.getFirst().before();
        int peak = progression.stream().mapToInt(RatingPoint::rating).max().orElseThrow();
        long wins = games.stream().filter(item -> item.match().outcome().equals("WIN")).count();
        long losses = games.stream().filter(item -> item.match().outcome().equals("LOSS")).count();
        return new QueueRating(games.getLast().rating(), games.size(), wins, losses, games.size() - wins - losses,
                Math.max(initial, peak), initial, progression);
    }

    private static ResponseStatusException profileUnavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Player profile unavailable");
    }
}
