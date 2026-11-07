package io.rbgs.api.ratings;

import io.rbgs.api.matchmaking.ConfirmedRatingStore;
import io.rbgs.api.matchmaking.ConfirmedRatingStore.Queue;
import io.rbgs.api.matchmaking.ConfirmedRatingStore.Snapshot;
import io.rbgs.api.matchmaking.SoloQueueCandidate;
import io.rbgs.api.matchmaking.SoloQueueMatcher.Proposal;
import io.rbgs.api.matches.MatchEntity;
import io.rbgs.api.matches.MatchEvidencePolicy;
import io.rbgs.api.matches.MatchMode;
import io.rbgs.api.seasons.RatingSubjectType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RatingResults {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate db;
    private final ConfirmedRatingStore confirmed;

    @Value("${rbgs.rating.enabled:false}")
    private boolean enabled;

    private enum Outcome {
        A(1), B(0), DRAW(0.5), UNRATED(0);

        private final double scoreA;

        Outcome(double scoreA) {
            this.scoreA = scoreA;
        }
    }

    private record FrozenMatch(Proposal proposal, RatingSubjectType ratingSubjectType, List<Snapshot> ratingInputs) {}
    private record Scope(UUID season, Queue queue, long position) {}
    private record Revision(long version, Outcome outcome) {}
    private record ReplayMatch(UUID id, long position, FrozenMatch frozen, Outcome outcome, String algorithm) {}
    private record Rating(int mmr, int visible, int games, int captainAssignments) {}

    public boolean enabled() {
        return enabled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void freeze(UUID match, UUID room, UUID season, Queue queue, RatingSubjectType type, Proposal proposal) {
        lock();
        var players = new ArrayList<>(proposal.teamA());
        players.addAll(proposal.teamB());
        var inputs = players.stream()
                .map(player -> confirmed.confirmed(season, queue, type, subject(type, player)).orElseThrow())
                .toList();
        long position = db.queryForObject("""
                INSERT INTO rbgs.rating_queue_sequence AS sequence (season_id, queue_type, last_position)
                VALUES (?, ?, 1)
                ON CONFLICT (season_id, queue_type)
                DO UPDATE SET last_position = sequence.last_position + 1
                RETURNING last_position
                """, Long.class, season, queue.name());
        db.update("""
                INSERT INTO rbgs.match_competitive_context
                    (match_id, room_id, season_id, queue_type, rating_order, snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, match, room, season, queue.name(), position,
                JSON.writeValueAsString(new FrozenMatch(proposal, type, inputs)), Timestamp.from(Instant.now()));
    }

    /** Called in the result transaction after the authoritative match version has been flushed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void synchronize(MatchEntity match) {
        if (!enabled || !eligible(match)) return;
        var scope = db.query("""
                SELECT season_id, queue_type, rating_order FROM rbgs.match_competitive_context WHERE match_id = ?
                """, (row, i) -> new Scope(row.getObject("season_id", UUID.class),
                Queue.valueOf(row.getString("queue_type")), row.getLong("rating_order")), match.getId())
                .stream().findFirst().orElse(null);
        if (scope == null) return;

        lock();
        var outcome = outcome(match);
        var last = latest(match.getId()).orElse(null);
        if (last == null ? outcome == Outcome.UNRATED
                : last.version() >= match.getResultVersion() || last.outcome() == outcome) return;

        db.update("""
                INSERT INTO rbgs.rating_result_revisions (match_id, result_version, outcome, algorithm)
                VALUES (?, ?, ?, ?)
                """, match.getId(), match.getResultVersion(), outcome.name(), EloRating.VERSION);
        replay(scope, match.getId(), match.getResultVersion());
    }

    public boolean applied(MatchEntity match) {
        if (!eligible(match)) return false;
        var result = outcome(match);
        return result != Outcome.UNRATED
                && latest(match.getId()).filter(revision -> revision.outcome() == result).isPresent();
    }

    private Optional<Revision> latest(UUID match) {
        return db.query("""
                SELECT result_version, outcome FROM rbgs.rating_result_revisions
                WHERE match_id = ? ORDER BY result_version DESC LIMIT 1
                """, (row, i) -> new Revision(row.getLong("result_version"), Outcome.valueOf(row.getString("outcome"))),
                match).stream().findFirst();
    }

    private void replay(Scope scope, UUID triggerMatch, long triggerVersion) {
        var history = db.query("""
                SELECT c.match_id, c.rating_order, c.snapshot, r.outcome, r.algorithm
                FROM rbgs.match_competitive_context c
                LEFT JOIN LATERAL (
                    SELECT outcome, algorithm FROM rbgs.rating_result_revisions
                    WHERE match_id = c.match_id ORDER BY result_version DESC LIMIT 1
                ) r ON true
                WHERE c.season_id = ? AND c.queue_type = ?
                ORDER BY c.rating_order
                """, (row, i) -> {
            String value = row.getString("outcome");
            return new ReplayMatch(row.getObject("match_id", UUID.class), row.getLong("rating_order"),
                    JSON.readValue(row.getString("snapshot"), FrozenMatch.class),
                    value == null ? null : Outcome.valueOf(value), row.getString("algorithm"));
        }, scope.season(), scope.queue().name());

        var ratings = new HashMap<UUID, Rating>();
        var affected = new TreeMap<UUID, RatingSubjectType>();
        var movements = new ArrayList<Object[]>();
        for (var match : history) {
            var frozen = match.frozen();
            for (var input : frozen.ratingInputs()) {
                ratings.putIfAbsent(input.subjectId(), new Rating(input.mmr(), input.visibleRating(),
                        input.gamesPlayed(), input.priorCaptainAssignments()));
                if (match.position() >= scope.position() && match.outcome() != null)
                    affected.put(input.subjectId(), input.subjectType());
            }
            if (match.outcome() == null || match.outcome() == Outcome.UNRATED) continue;
            if (!EloRating.VERSION.equals(match.algorithm())) throw new IllegalStateException("Unknown rating algorithm");
            var proposal = frozen.proposal();
            var type = frozen.ratingSubjectType();
            double meanA = mean(proposal.teamA(), ratings, type);
            double meanB = mean(proposal.teamB(), ratings, type);
            var audit = match.position() >= scope.position() ? movements : null;
            applyTeam(match.id(), "A", proposal.teamA(), proposal.captainA(), type, ratings,
                    meanA, meanB, match.outcome().scoreA, triggerMatch, triggerVersion, audit);
            applyTeam(match.id(), "B", proposal.teamB(), proposal.captainB(), type, ratings,
                    meanB, meanA, 1 - match.outcome().scoreA, triggerMatch, triggerVersion, audit);
        }

        if (!movements.isEmpty()) db.batchUpdate("""
                INSERT INTO rbgs.rating_movements (replay_match_id, replay_result_version, match_id, subject_id,
                    team, mmr_before, mmr_after, visible_before, visible_after, team_mmr, opponent_mmr)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, movements);
        var updates = new ArrayList<Object[]>();
        affected.forEach((id, type) -> {
            var value = ratings.get(id);
            updates.add(new Object[]{value.mmr(), value.visible(), value.games(), value.captainAssignments(),
                    scope.season(), scope.queue().name(), type.name(), id,
                    value.mmr(), value.visible(), value.games(), value.captainAssignments()});
        });
        db.batchUpdate("""
                UPDATE rbgs.competitive_ratings
                SET mmr = ?, visible_rating = ?, games_played = ?, captain_assignments = ?,
                    version = version + 1, confirmed_at = CURRENT_TIMESTAMP
                WHERE season_id = ? AND queue_type = ? AND subject_type = ? AND subject_id = ?
                  AND (mmr, visible_rating, games_played, captain_assignments) IS DISTINCT FROM (?, ?, ?, ?)
                """, updates);
    }

    private static void applyTeam(UUID match, String team, List<SoloQueueCandidate> players, UUID captain,
            RatingSubjectType type, Map<UUID, Rating> ratings, double own, double opponent, double score,
            UUID trigger, long version, List<Object[]> audit) {
        int mmrDelta = EloRating.delta(score, own, opponent);
        for (var player : players) {
            UUID id = subject(type, player);
            var before = ratings.get(id);
            int visibleDelta = EloRating.delta(score, before.visible(), opponent);
            var after = new Rating(EloRating.apply(before.mmr(), mmrDelta), EloRating.apply(before.visible(), visibleDelta),
                    before.games() + 1, before.captainAssignments() + (player.accountId().equals(captain) ? 1 : 0));
            ratings.put(id, after);
            if (audit != null) audit.add(new Object[]{trigger, version, match, id, team,
                    before.mmr(), after.mmr(), before.visible(), after.visible(), own, opponent});
        }
    }

    private static double mean(List<SoloQueueCandidate> team, Map<UUID, Rating> ratings, RatingSubjectType type) {
        return team.stream().mapToInt(player -> ratings.get(subject(type, player)).mmr()).average().orElseThrow();
    }

    private static UUID subject(RatingSubjectType type, SoloQueueCandidate player) {
        return type == RatingSubjectType.ACCOUNT ? player.accountId() : player.characterId();
    }

    private static boolean eligible(MatchEntity match) {
        return match.getMode() == MatchMode.MATCHMADE && match.getEvidencePolicy() == MatchEvidencePolicy.FULL_ROSTER;
    }

    private static Outcome outcome(MatchEntity match) {
        if (match.getCancelledAt() != null || !Set.of("VERIFIED", "RESOLVED").contains(match.getVerificationStatus()))
            return Outcome.UNRATED;
        if ("DRAW".equals(match.getOutcomeKind())) return Outcome.DRAW;
        if ("WIN".equals(match.getOutcomeKind())) {
            if ("A".equals(match.getWinningTeam())) return Outcome.A;
            if ("B".equals(match.getWinningTeam())) return Outcome.B;
        }
        throw new IllegalStateException("Confirmed match has no valid rating outcome");
    }

    private void lock() {
        // A result worker can handle several queues in one transaction. Use a shared lock order.
        db.execute("SELECT pg_advisory_xact_lock(74125, 12)");
    }
}
