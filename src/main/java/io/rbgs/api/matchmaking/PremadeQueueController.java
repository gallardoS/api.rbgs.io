package io.rbgs.api.matchmaking;

import io.rbgs.api.identity.AccountService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/me/premade-queue")
public class PremadeQueueController {
    private final AccountService accounts;
    private final PremadeQueueService queue;
    private final SoloQueueEvents events;
    @GetMapping
    public ResponseEntity<PremadeQueueService.Status> status(Authentication auth,CsrfToken csrf) {
        csrf.getToken();return reply(actor(auth),HttpStatus.OK);
    }
    @PostMapping
    public ResponseEntity<PremadeQueueService.Status> enter(Authentication auth,@Valid @RequestBody PremadeQueueService.Admission request) {
        UUID account=actor(auth);return reply(account,queue.enter(account,request)?HttpStatus.OK:HttpStatus.CONFLICT);
    }
    @DeleteMapping("/{entry}")
    public ResponseEntity<Void> cancel(Authentication auth,@PathVariable UUID entry) {
        queue.cancel(actor(auth),entry);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/rooms/{room}/response")
    public ResponseEntity<PremadeQueueService.Status> respond(Authentication auth,@PathVariable UUID room,@Valid @RequestBody SoloQueueController.Response body) {
        UUID account=actor(auth);queue.respond(account,room,body.accept());return reply(account,HttpStatus.OK);
    }
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication auth,HttpSession session,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return events.open(actor(auth),session.getId());
    }
    private UUID actor(Authentication auth) {
        var account=accounts.currentUser(auth);if(account==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return account.id();
    }
    private ResponseEntity<PremadeQueueService.Status> reply(UUID account,HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(queue.status(account));
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missing() { return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatus(HttpStatus.NOT_FOUND)); }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ProblemDetail> forbidden() { return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatus(HttpStatus.FORBIDDEN)); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(IllegalStateException error) { return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,error.getMessage())); }
}
