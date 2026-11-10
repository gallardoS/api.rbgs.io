package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.MatchRole;
import io.rbgs.api.identity.AccountService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tester/queue")
class TesterQueueController {
    private final AccountService accounts;

    @GetMapping
    ResponseEntity<?> simulate(Authentication auth, @RequestParam String scenario) {
        if (!Set.of("NORMAL", "NO_CAPTAINS", "NO_HEALERS").contains(scenario))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var actor = accounts.currentUser(auth);
        var now = Instant.now();
        var season = UUID.randomUUID();
        var candidates = new ArrayList<SoloQueueCandidate>();
        for (int i = 0; i < 20; i++) {
            var role = i < 2 ? MatchRole.FC
                    : i < 8 && !scenario.equals("NO_HEALERS") ? MatchRole.HEALER : MatchRole.DPS;
            candidates.add(new SoloQueueCandidate(i == 19 ? actor.id() : UUID.randomUUID(),
                    UUID.randomUUID(), season, "EU", "TESTER", "TESTER", role,
                    !scenario.equals("NO_CAPTAINS"), now, 1400 + i * 3, "Warrior", 0));
        }
        var proposal = new SoloQueueMatcher().match(candidates, now, (a, b) -> true);
        if (proposal.isEmpty()) return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("serverTime", now, "waiting", 20));
        var match = proposal.get();
        var room = new SoloQueueRooms.Room(UUID.randomUUID(), "SIMULATION", "PROPOSED",
                now.plusSeconds(60), null, null, 0,
                members(match.teamA(), match.captainA(), actor.id(), candidates),
                members(match.teamB(), match.captainB(), actor.id(), candidates), false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("serverTime", now, "room", room));
    }

    private List<SoloQueueRooms.Member> members(List<SoloQueueCandidate> team, UUID captain, UUID actor,
            List<SoloQueueCandidate> candidates) {
        return team.stream().map(player -> new SoloQueueRooms.Member(player.accountId(),
                "Test player " + (candidates.indexOf(player) + 1), player.role().name(),
                captain.equals(player.accountId()), !actor.equals(player.accountId()), false)).toList();
    }
}
