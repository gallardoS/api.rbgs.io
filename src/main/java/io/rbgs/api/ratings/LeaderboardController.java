package io.rbgs.api.ratings;

import io.rbgs.api.matchmaking.ConfirmedRatingStore.Queue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "rbgs.rating.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LeaderboardController {
    public static final String PATH = "/api/v1/local/leaderboard";
    public static final String PROFILE_PATH = PATH + "/profile";

    private final LeaderboardService leaderboard;

    @GetMapping(PATH)
    public ResponseEntity<LeaderboardService.Page> page(
            @RequestParam(defaultValue = "SOLO") Queue queue,
            @RequestParam(required = false) UUID season,
            @RequestParam(defaultValue = "") @Size(max = 100) String search,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(LeaderboardService.MAX_OFFSET) int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(leaderboard.page(queue, season, search.strip(), limit, offset));
    }

    @GetMapping(value = PROFILE_PATH, params = "character")
    public ResponseEntity<LeaderboardService.Profile> profile(@RequestParam UUID season, @RequestParam UUID character) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(leaderboard.profile(season, character));
    }

    @GetMapping(value = PROFILE_PATH, params = "!character")
    public ResponseEntity<LeaderboardService.Profile> namedProfile(
            @RequestParam(required = false) UUID season,
            @RequestParam @Size(min = 1, max = 16) String region,
            @RequestParam @Size(min = 1, max = 100) String realm,
            @RequestParam @Size(min = 1, max = 100) String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(leaderboard.namedProfile(season, region, realm, name));
    }
}
