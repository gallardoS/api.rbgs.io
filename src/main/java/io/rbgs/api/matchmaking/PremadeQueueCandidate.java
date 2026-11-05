package io.rbgs.api.matchmaking;

import io.rbgs.api.seasons.RatingSubjectType;
import java.time.Instant;
import java.util.*;

/** Server-owned premade-rating snapshot, never populated from a browser request. */
public record PremadeQueueCandidate(UUID groupId, long revision, UUID captainId,
        RatingSubjectType ratingSubjectType, List<SoloQueueCandidate> members) {
    public PremadeQueueCandidate {
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(captainId);
        Objects.requireNonNull(ratingSubjectType);
        members = List.copyOf(members);
        if (revision < 0 || members.size() != 10
                || members.stream().map(SoloQueueCandidate::accountId).distinct().count() != 10
                || members.stream().noneMatch(c -> c.accountId().equals(captainId)))
            throw new IllegalArgumentException("A premade requires ten distinct accounts and its captain");
        var first = members.getFirst();
        if (members.stream().anyMatch(c -> !c.seasonId().equals(first.seasonId())
                || !c.region().equals(first.region()) || !c.ruleset().equals(first.ruleset())
                || !c.groupingKey().equals(first.groupingKey()) || !c.enteredAt().equals(first.enteredAt())))
            throw new IllegalArgumentException("All premade members must share verified grouping and queue context");
        members = members.stream().sorted(Comparator.comparing(SoloQueueCandidate::accountId)).toList();
    }

    public Instant enteredAt() { return members.getFirst().enteredAt(); }
    public double meanMmr() { return members.stream().mapToLong(SoloQueueCandidate::mmr).sum() / 10.0; }
    public UUID seasonId() { return members.getFirst().seasonId(); }
    public String region() { return members.getFirst().region(); }
    public String ruleset() { return members.getFirst().ruleset(); }
    public String groupingKey() { return members.getFirst().groupingKey(); }
}
