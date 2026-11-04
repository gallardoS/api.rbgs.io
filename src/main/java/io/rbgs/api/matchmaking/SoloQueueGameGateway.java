package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.seasons.SeasonEntity;
import java.time.Instant;
import java.util.*;

/** Server-verified, already persisted signals. Implementations must not perform provider network calls under queue locks. */
public interface SoloQueueGameGateway {
    record VerifiedPlayer(UUID accountId,UUID seasonId,long seasonVersion,UUID characterId,long selectionVersion,
            UUID deviceId,String product,String region,String ruleset,String groupingKey,String characterClass,
            Instant observedAt,Instant validUntil) { }
    Optional<VerifiedPlayer> inspect(UUID account,SeasonEntity season,SeasonSelection selection);
    boolean canOppose(String ruleset,String first,String second);
    boolean handoffAvailable();
    /** Create the shared Match/assignments transactionally after every participant accepts. Never manufacture game profiles. */
    UUID createMatch(SoloQueueMatcher.Proposal proposal,Instant now);
}
