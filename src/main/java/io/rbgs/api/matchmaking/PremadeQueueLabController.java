package io.rbgs.api.matchmaking;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController @Profile("local & soloq-lab") @RequiredArgsConstructor
@RequestMapping("/api/v1/local/premade")
public class PremadeQueueLabController {
    static final String ACTOR="RBGS_PREMADE_LAB_ACTOR";
    private final PremadeQueueLab lab;
    private final PremadeQueueLabEvents events;
    public record Opponents(@Min(1) @Max(9) int count) { }
    public record MatchAction(@NotNull PremadeQueueLab.MatchAction action, @Pattern(regexp="[AB]") String winner) { }
    @GetMapping("/setup")
    public ResponseEntity<PremadeQueueLab.Setup> setup(HttpSession session,CsrfToken csrf) {
        csrf.getToken();var setup=lab.setup((UUID)session.getAttribute(ACTOR));
        session.setAttribute(ACTOR,setup.accountId());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup);
    }
    @PostMapping("/reset")
    public ResponseEntity<PremadeQueueLab.Setup> reset(HttpSession session,CsrfToken csrf) {
        lab.reset();session.removeAttribute(ACTOR);return setup(session,csrf);
    }
    @PostMapping("/actor")
    public ResponseEntity<PremadeQueueLab.Setup> actor(HttpSession session,CsrfToken csrf,@Valid @RequestBody SoloQueueLabController.Actor request) {
        if(lab.setup(null).players().stream().noneMatch(p->p.id().equals(request.accountId())))throw new NoSuchElementException();
        session.setAttribute(ACTOR,request.accountId());return setup(session,csrf);
    }
    @GetMapping
    public ResponseEntity<PremadeQueueService.Status> status(HttpSession session,CsrfToken csrf) { csrf.getToken();return reply(session); }
    @PostMapping
    public ResponseEntity<PremadeQueueService.Status> enter(HttpSession session,@Valid @RequestBody PremadeQueueService.Admission request) {
        lab.enter(actor(session),request);return reply(session);
    }
    @DeleteMapping("/{entry}")
    public ResponseEntity<PremadeQueueService.Status> cancel(HttpSession session,@PathVariable UUID entry) { lab.cancel(actor(session),entry);return reply(session); }
    @PostMapping("/opponents")
    public ResponseEntity<PremadeQueueService.Status> opponents(HttpSession session,@Valid @RequestBody Opponents body) { lab.addOpponents(actor(session),body.count());return reply(session); }
    @PostMapping("/captain")
    public ResponseEntity<PremadeQueueService.Status> captain(HttpSession session,@Valid @RequestBody SoloQueueLabController.Actor body) {
        lab.transferCaptain(actor(session),body.accountId());return reply(session);
    }
    @PostMapping("/rooms/{room}/response")
    public ResponseEntity<PremadeQueueService.Status> respond(HttpSession session,@PathVariable UUID room,@Valid @RequestBody SoloQueueController.Response body) {
        lab.respond(actor(session),room,body.accept());return reply(session);
    }
    @GetMapping("/rooms/{room}")
    public ResponseEntity<PremadeQueueLab.PastRoom> pastRoom(HttpSession session,@PathVariable UUID room) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lab.pastRoom(actor(session),room));
    }
    @PostMapping("/rooms/{room}/test-acceptances")
    public ResponseEntity<PremadeQueueService.Status> bots(HttpSession session,@PathVariable UUID room) { lab.acceptBots(actor(session),room);return reply(session); }
    @PostMapping("/rooms/{room}/simulation")
    public ResponseEntity<PremadeQueueService.Status> advance(HttpSession session,@PathVariable UUID room,
            @Valid @RequestBody MatchAction request) {
        lab.advance(actor(session),room,request.action(),request.winner());
        return reply(session);
    }
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpSession session,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return events.open(session.getId(),actor(session));
    }
    private UUID actor(HttpSession session) {
        var actor=(UUID)session.getAttribute(ACTOR);if(actor==null)throw new NoSuchElementException();return actor;
    }
    private ResponseEntity<PremadeQueueService.Status> reply(HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lab.status(actor(session)));
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missing() { return problem(HttpStatus.NOT_FOUND,"Simulation participant or room unavailable"); }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ProblemDetail> forbidden(SecurityException error) { return problem(HttpStatus.FORBIDDEN,error.getMessage()); }
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException error) { return problem(HttpStatus.CONFLICT,error.getMessage()); }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(status,detail));
    }
}
