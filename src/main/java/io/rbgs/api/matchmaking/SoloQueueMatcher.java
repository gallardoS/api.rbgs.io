package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.MatchRole;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiPredicate;

/** Pure, deterministic selection. No client/faction compatibility assumptions live here. */
public final class SoloQueueMatcher {
    public static final Comparator<SoloQueueCandidate> ORDER = Comparator.comparing(SoloQueueCandidate::enteredAt)
            .thenComparing(SoloQueueCandidate::accountId);
    private static final Comparator<SoloQueueCandidate> CAPTAIN = Comparator.comparingInt(SoloQueueCandidate::priorCaptainAssignments)
            .thenComparing(ORDER);
    private static final Comparator<SoloQueueCandidate> LOTTERY = Comparator
            .comparing((SoloQueueCandidate c)->c.captainLottery()==null?c.accountId():c.captainLottery())
            .thenComparing(SoloQueueCandidate::accountId);

    static SoloQueueCandidate captain(List<SoloQueueCandidate> team) {
        var volunteer=team.stream().filter(SoloQueueCandidate::captainConsent).min(CAPTAIN);
        if (volunteer.isPresent()) return volunteer.get();
        if (team.stream().allMatch(c->c.gamesPlayed()==0)) return team.stream().min(LOTTERY).orElseThrow();
        return team.stream().min(Comparator.comparingInt(SoloQueueCandidate::gamesPlayed).reversed()
                .thenComparing(Comparator.comparingInt(SoloQueueCandidate::visibleRating).reversed())
                .thenComparing(LOTTERY)).orElseThrow();
    }

    public record Composition(int fc, int healers, int dps) {
        int count(MatchRole role) { return switch (role) { case FC -> fc; case HEALER -> healers; case DPS -> dps; }; }
    }
    public record Proposal(List<SoloQueueCandidate> teamA, List<SoloQueueCandidate> teamB,
            UUID captainA, UUID captainB, Composition composition) {
        public Proposal { teamA = List.copyOf(teamA); teamB = List.copyOf(teamB); }
    }
    public static List<Composition> compositions(Duration wait) {
        var result = new ArrayList<>(List.of(new Composition(1, 3, 6), new Composition(1, 2, 7)));
        if (wait.compareTo(Duration.ofMinutes(2)) >= 0) result.addAll(List.of(new Composition(0, 3, 7), new Composition(0, 2, 8)));
        if (wait.compareTo(Duration.ofMinutes(4)) >= 0) result.addAll(List.of(new Composition(1, 1, 8), new Composition(0, 1, 9)));
        return List.copyOf(result);
    }

    public Optional<Proposal> match(List<SoloQueueCandidate> snapshot, Instant now,
            BiPredicate<String, String> canOppose) {
        Objects.requireNonNull(canOppose);
        return matchWithPolicy(snapshot,now,(ruleset,a,b)->canOppose.test(a,b));
    }
    @FunctionalInterface public interface GroupingPolicy { boolean canOppose(String ruleset,String first,String second); }
    public Optional<Proposal> matchWithPolicy(List<SoloQueueCandidate> snapshot,Instant now,GroupingPolicy canOppose) {
        Objects.requireNonNull(canOppose);
        var queue = snapshot.stream().sorted(ORDER).toList();
        if (queue.stream().map(SoloQueueCandidate::accountId).distinct().count() != queue.size())
            throw new IllegalArgumentException("An account may enter only once");
        if (queue.stream().anyMatch(c -> c.enteredAt().isAfter(now))) throw new IllegalArgumentException("Future queue entry");
        // The first feasible oldest entrant owns the relaxation clock, never an unrelated partition.
        for (int anchorIndex = 0; anchorIndex <= queue.size() - 20; anchorIndex++) {
            var anchor = queue.get(anchorIndex);
            var compatible = queue.subList(anchorIndex, queue.size()).stream().filter(c -> c.seasonId().equals(anchor.seasonId())
                    && c.region().equals(anchor.region()) && c.ruleset().equals(anchor.ruleset())).toList();
            if (compatible.size() < 20) continue;
            for (var composition : compositions(Duration.between(anchor.enteredAt(), now))) {
                List<SoloQueueCandidate> bestRoster = null;
                var keys = compatible.stream().map(SoloQueueCandidate::groupingKey).distinct().sorted().toList();
                // Each team must be groupable. Cross-faction grouping must be explicitly proven by its key.
                for (String keyA : keys) for (String keyB : keys) {
                    if (keyA.compareTo(keyB) > 0 || !canOppose.canOppose(anchor.ruleset(),keyA, keyB)) continue;
                    var pool = compatible.stream().filter(c -> c.groupingKey().equals(keyA) || c.groupingKey().equals(keyB)).toList();
                    // Captains are assigned after roster selection; missing volunteers never displace older entrants.
                    var roster = oldestRoster(pool, List.of(anchor), composition, keyA, keyB);
                    if (roster != null && (bestRoster == null || compareRoster(roster, bestRoster) < 0)) bestRoster = roster;
                }
                if (bestRoster != null) return Optional.of(balance(bestRoster, composition));
            }
        }
        return Optional.empty();
    }

    private List<SoloQueueCandidate> oldestRoster(List<SoloQueueCandidate> pool, List<SoloQueueCandidate> required,
            Composition composition, String keyA, String keyB) {
        var selected = new LinkedHashSet<>(required);
        boolean shared = keyA.equals(keyB);
        for (String key : shared ? List.of(keyA) : List.of(keyA, keyB)) for (var role : MatchRole.values()) {
            int quota = composition.count(role) * (shared ? 2 : 1);
            long have = selected.stream().filter(c -> c.groupingKey().equals(key) && c.role() == role).count();
            if (have > quota) return null;
            for (var c : pool) if (have < quota && c.groupingKey().equals(key) && c.role() == role && selected.add(c)) have++;
            if (have != quota) return null;
        }
        return selected.size() == 20 ? selected.stream().sorted(ORDER).toList() : null;
    }

    private static int compareRoster(List<SoloQueueCandidate> a, List<SoloQueueCandidate> b) {
        for (int i = 0; i < a.size(); i++) { int c = ORDER.compare(a.get(i), b.get(i)); if (c != 0) return c; }
        return 0;
    }

    private Proposal balance(List<SoloQueueCandidate> roster, Composition composition) {
        var search = new Balance(roster, composition);
        search.visit(0, new ArrayList<>(), new int[MatchRole.values().length], null);
        return Objects.requireNonNull(search.best);
    }

    private static final class Balance {
        final List<SoloQueueCandidate> roster;
        final Composition composition;
        Proposal best;
        long bestMmrGap = Long.MAX_VALUE;
        int bestClassGap = Integer.MAX_VALUE;
        int bestVolunteerTeams = -1;
        Balance(List<SoloQueueCandidate> roster, Composition composition) { this.roster = roster; this.composition = composition; }

        void visit(int index, List<SoloQueueCandidate> a, int[] counts, String key) {
            if (a.size() > 10 || a.size() + roster.size() - index < 10) return;
            if (index == roster.size()) {
                if (a.size() != 10) return;
                var b = roster.stream().filter(c -> !a.contains(c)).toList();
                if (b.stream().map(SoloQueueCandidate::groupingKey).distinct().count() != 1) return;
                int volunteerTeams=(a.stream().anyMatch(SoloQueueCandidate::captainConsent)?1:0)
                        +(b.stream().anyMatch(SoloQueueCandidate::captainConsent)?1:0);
                long gap = Math.abs(a.stream().mapToLong(SoloQueueCandidate::mmr).sum() - b.stream().mapToLong(SoloQueueCandidate::mmr).sum());
                var classes = new HashMap<String, Integer>();
                a.forEach(c -> classes.merge(c.characterClass(), 1, Integer::sum));
                b.forEach(c -> classes.merge(c.characterClass(), -1, Integer::sum));
                int classGap = classes.values().stream().mapToInt(Math::abs).sum();
                if (volunteerTeams>bestVolunteerTeams || volunteerTeams==bestVolunteerTeams
                        && (gap < bestMmrGap || gap == bestMmrGap && classGap < bestClassGap)) {
                    bestVolunteerTeams=volunteerTeams; bestMmrGap = gap; bestClassGap = classGap;
                    best = new Proposal(a, b, captain(a).accountId(), captain(b).accountId(), composition);
                }
                return;
            }
            var c = roster.get(index);
            int role = c.role().ordinal();
            if (counts[role] < composition.count(c.role()) && (key == null || key.equals(c.groupingKey()))) {
                a.add(c); counts[role]++;
                visit(index + 1, a, counts, c.groupingKey());
                counts[role]--; a.removeLast();
            }
            // Fix the oldest entrant to A to remove mirrored duplicate partitions.
            if (index != 0) visit(index + 1, a, counts, key);
        }
    }
}
