package io.rbgs.api.matchmaking;

import io.rbgs.api.identity.AccountService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me/solo-queue")
public class SoloQueueController {
    private final AccountService accounts;
    private final SoloQueueService queue;
    private final SoloQueueEvents events;
    public record Response(@NotNull Boolean accept) { }

    @GetMapping
    public ResponseEntity<SoloQueueService.Status> status(Authentication auth, CsrfToken csrf) {
        csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queue.status(actor(auth)));
    }
    @PostMapping
    public ResponseEntity<SoloQueueService.Status> enter(Authentication auth,@Valid @RequestBody SoloQueueService.Admission request) {
        UUID account=actor(auth);boolean admitted=queue.enter(account,request);
        return ResponseEntity.status(admitted?HttpStatus.OK:HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(queue.status(account));
    }
    @DeleteMapping("/{ticket}")
    public ResponseEntity<Void> cancel(Authentication auth, @PathVariable UUID ticket) {
        try { queue.cancel(actor(auth), ticket); }
        catch (NoSuchElementException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/rooms/{room}/response")
    public ResponseEntity<SoloQueueService.Status> respond(Authentication auth,@PathVariable UUID room,@Valid @RequestBody Response response) {
        UUID account=actor(auth);
        try{queue.respond(account,room,response.accept());}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queue.status(account));
    }
    @GetMapping(value="/events",produces=org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication auth,HttpSession session,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        UUID account=actor(auth);return events.open(account,session.getId());
    }
    private UUID actor(Authentication auth) {
        var account = accounts.currentUser(auth);
        if (account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return account.id();
    }
}
