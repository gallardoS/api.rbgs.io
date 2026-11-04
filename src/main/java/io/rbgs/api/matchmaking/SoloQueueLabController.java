package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.MatchRole;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("local & soloq-lab")
@RequestMapping("/api/v1/local/soloq")
@RequiredArgsConstructor
public class SoloQueueLabController {
    static final String ACTOR="RBGS_SOLOQ_LAB_ACTOR";
    private final SoloQueueLab lab;
    private final SoloQueueStore store;
    private final SoloQueueRooms rooms;
    private final SoloQueueLabEvents events;
    public record Reset(@NotNull String scenario) { }
    public record Actor(@NotNull UUID accountId) { }
    public record Selection(@NotNull MatchRole role,boolean captainConsent) { }
    public record Players(@Min(19) @Max(99) int count) { }
    public record RoomResponse(boolean accept) { }

    @GetMapping
    public ResponseEntity<SoloQueueLab.Snapshot> status(HttpSession session,CsrfToken csrf) {
        csrf.getToken(); return reply(session);
    }
    @PostMapping("/reset")
    public ResponseEntity<SoloQueueLab.Snapshot> reset(HttpSession session,@Valid @RequestBody Reset body) {
        lab.reset(body.scenario()); session.removeAttribute(ACTOR); return reply(session);
    }
    @PostMapping("/actor")
    public ResponseEntity<SoloQueueLab.Snapshot> actor(HttpSession session,@Valid @RequestBody Actor body) {
        if (lab.snapshot(null).players().stream().noneMatch(p->p.id().equals(body.accountId()))) throw new NoSuchElementException();
        session.setAttribute(ACTOR,body.accountId()); return reply(session);
    }
    @PutMapping("/selection")
    public ResponseEntity<SoloQueueLab.Snapshot> selection(HttpSession session,@Valid @RequestBody Selection body) {
        lab.choose(actor(session),body.role(),body.captainConsent()); return reply(session);
    }
    @PostMapping("/queue")
    public ResponseEntity<SoloQueueLab.Snapshot> enter(HttpSession session) { lab.enter(actor(session)); return reply(session); }
    @DeleteMapping("/queue/{ticket}")
    public ResponseEntity<SoloQueueLab.Snapshot> cancel(HttpSession session,@PathVariable UUID ticket) {
        store.cancel(actor(session),ticket); return reply(session);
    }
    @PostMapping("/players")
    public ResponseEntity<SoloQueueLab.Snapshot> players(HttpSession session,@Valid @RequestBody Players body) {
        lab.addPlayers(actor(session),body.count()); return reply(session);
    }
    @PostMapping("/rooms/{room}/response")
    public ResponseEntity<SoloQueueLab.Snapshot> respond(HttpSession session,@PathVariable UUID room,@RequestBody RoomResponse body) {
        rooms.respond(actor(session),room,body.accept()); return reply(session);
    }
    @PostMapping("/rooms/{room}/test-acceptances")
    public ResponseEntity<SoloQueueLab.Snapshot> bots(HttpSession session,@PathVariable UUID room) {
        lab.acceptBots(actor(session),room); return reply(session);
    }
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpSession session,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return events.open(session.getId(),actor(session));
    }
    private UUID actor(HttpSession session) {
        var snapshot=lab.snapshot((UUID)session.getAttribute(ACTOR));
        if (snapshot.accountId()==null) throw new IllegalStateException("Create a simulation first");
        session.setAttribute(ACTOR,snapshot.accountId()); return snapshot.accountId();
    }
    private ResponseEntity<SoloQueueLab.Snapshot> reply(HttpSession session) {
        var state=lab.snapshot((UUID)session.getAttribute(ACTOR));
        if (state.accountId()!=null) session.setAttribute(ACTOR,state.accountId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state);
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missing() {
        return ResponseEntity.status(404).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,"Simulation participant or room unavailable"));
    }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,e.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,e.getMessage()));
    }
}
