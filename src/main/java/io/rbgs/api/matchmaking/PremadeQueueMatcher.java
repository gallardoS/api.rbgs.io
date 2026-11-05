package io.rbgs.api.matchmaking;

import java.time.*;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Pure selection; callers supply the MMR widening policy and verified game restrictions. */
public final class PremadeQueueMatcher {
    private static final Comparator<PremadeQueueCandidate> ORDER = Comparator
            .comparing(PremadeQueueCandidate::enteredAt).thenComparing(PremadeQueueCandidate::groupId);

    public record Proposal(PremadeQueueCandidate teamA, PremadeQueueCandidate teamB) {
        SoloQueueMatcher.Proposal sharedRoom() {
            // Composition is a SoloQ concept. Premade roles are display metadata only.
            return new SoloQueueMatcher.Proposal(teamA.members(), teamB.members(),
                    teamA.captainId(), teamB.captainId(), null);
        }
    }

    public Optional<Proposal> match(List<PremadeQueueCandidate> candidates, Instant now,
            SoloQueueMatcher.GroupingPolicy opposition, ToDoubleFunction<Duration> mmrWindow) {
        Objects.requireNonNull(opposition);
        Objects.requireNonNull(mmrWindow);
        var queue = candidates.stream().sorted(ORDER).toList();
        var accounts = new HashSet<UUID>();
        var groups = new HashSet<UUID>();
        for (var group : queue) {
            if (!groups.add(group.groupId()) || group.enteredAt().isAfter(now))
                throw new IllegalArgumentException("Duplicate group or future queue entry");
            for (var player : group.members()) if (!accounts.add(player.accountId()))
                throw new IllegalArgumentException("An account may enter only once across premades");
        }
        // Oldest feasible group first. Its own wait controls widening; unrelated partitions cannot widen it.
        for (int i = 0; i < queue.size(); i++) {
            var anchor = queue.get(i);
            double window = mmrWindow.applyAsDouble(Duration.between(anchor.enteredAt(), now));
            if (!Double.isFinite(window) || window < 0) throw new IllegalArgumentException("Invalid MMR window");
            var opponent = queue.subList(i + 1, queue.size()).stream()
                    .filter(g -> g.seasonId().equals(anchor.seasonId()) && g.region().equals(anchor.region())
                            && g.ruleset().equals(anchor.ruleset()) && g.ratingSubjectType() == anchor.ratingSubjectType())
                    .filter(g -> opposition.canOppose(anchor.ruleset(), anchor.groupingKey(), g.groupingKey()))
                    .filter(g -> Math.abs(anchor.meanMmr() - g.meanMmr()) <= window)
                    .min(Comparator.<PremadeQueueCandidate>comparingDouble(g -> Math.abs(anchor.meanMmr() - g.meanMmr()))
                            .thenComparing(ORDER));
            if (opponent.isPresent()) return Optional.of(new Proposal(anchor, opponent.get()));
        }
        return Optional.empty();
    }
}
