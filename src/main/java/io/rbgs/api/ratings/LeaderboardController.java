package io.rbgs.api.ratings;

import io.rbgs.api.matchmaking.ConfirmedRatingStore.Queue;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LeaderboardController {
    public static final String PATH = "/api/v1/leaderboard";
    public static final String PROFILE_PATH = PATH + "/profile";
    public static final String LOCAL_PATH = "/api/v1/local/leaderboard";
    public static final String LOCAL_PROFILE_PATH = LOCAL_PATH + "/profile";

    private final LeaderboardService leaderboard;

    @GetMapping({PATH, LOCAL_PATH})
    public ResponseEntity<LeaderboardService.Page> page(
            HttpServletRequest request,
            @RequestParam(defaultValue = "SOLO") Queue queue,
            @RequestParam(required = false) UUID season,
            @RequestParam(required = false) LeaderboardService.Region region,
            @RequestParam(name = "class", required = false) @Size(max = 9) List<LeaderboardService.CharacterClass> classes,
            @RequestParam(defaultValue = "") @Size(max = 100) String search,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(LeaderboardService.MAX_OFFSET) int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(leaderboard.page(queue, season, region, classes == null ? List.of() : classes,
                        search.strip(), limit, offset, publicOnly(request)));
    }

    @GetMapping(value = {PROFILE_PATH, LOCAL_PROFILE_PATH}, params = "character")
    public ResponseEntity<LeaderboardService.Profile> profile(HttpServletRequest request, @RequestParam UUID season, @RequestParam UUID character) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(leaderboard.profile(season, character, publicOnly(request)));
    }

    @GetMapping(value = {PROFILE_PATH, LOCAL_PROFILE_PATH}, params = "!character")
    public ResponseEntity<LeaderboardService.Profile> namedProfile(
            HttpServletRequest request,
            @RequestParam(required = false) UUID season,
            @RequestParam @Size(min = 1, max = 16) String region,
            @RequestParam @Size(min = 1, max = 100) String realm,
            @RequestParam @Size(min = 1, max = 100) String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(leaderboard.namedProfile(season, region, realm, name, publicOnly(request)));
    }

    private static boolean publicOnly(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + LOCAL_PATH);
    }
}
