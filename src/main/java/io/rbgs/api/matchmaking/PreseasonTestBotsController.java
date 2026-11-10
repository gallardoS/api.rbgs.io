package io.rbgs.api.matchmaking;

import io.rbgs.api.identity.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tester/preseason-bots")
class PreseasonTestBotsController {
    private final AccountService accounts;
    private final PreseasonTestBots bots;

    @GetMapping
    ResponseEntity<PreseasonTestBots.Status> status(Authentication auth, CsrfToken csrf) {
        csrf.getToken();
        return response(bots.status(accounts.currentUser(auth).id()));
    }

    @PostMapping
    ResponseEntity<PreseasonTestBots.Status> start(Authentication auth, @RequestParam PreseasonTestBots.Queue queue) {
        return response(bots.start(accounts.currentUser(auth).id(), queue));
    }

    @DeleteMapping
    ResponseEntity<PreseasonTestBots.Status> stop(Authentication auth) {
        return response(bots.stop(accounts.currentUser(auth).id()));
    }

    private ResponseEntity<PreseasonTestBots.Status> response(PreseasonTestBots.Status status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }
}
