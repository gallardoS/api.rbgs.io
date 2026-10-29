package io.rbgs.api.groups;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class GroupController {
    private final GroupService service;
    private final GroupEventStreams events;
    private final io.rbgs.api.identity.AccountService accounts;
    public record Revision(@NotNull @PositiveOrZero Long expectedRevision) { }
    public record Invite(@NotNull @PositiveOrZero Long expectedRevision,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cntrl}]+#[0-9]+$") String battleTag) { }
    public record Target(@NotNull @PositiveOrZero Long expectedRevision, @NotNull UUID accountId) { }
    public record Join(@NotBlank @Size(max = 64) String code) { }
    public record Code(@NotNull @PositiveOrZero Long expectedRevision, @NotNull Boolean enabled) { }
    public record DisplayRole(@NotNull @PositiveOrZero Long expectedRoleVersion,
            @NotNull io.rbgs.api.characters.selection.MatchRole role) { }
    public record Ready(@NotNull @PositiveOrZero Long expectedRevision, @NotNull Boolean confirmed,
            UUID seasonId, @PositiveOrZero Long seasonVersion, @PositiveOrZero Long selectionVersion) { }

    @GetMapping(value = "/api/v1/me/group-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> events(Authentication auth,
            jakarta.servlet.http.HttpServletRequest request) {
        var profile = accounts.currentUser(auth);
        var session = request.getSession(false);
        if (profile == null || session == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(events.open(profile.id(), session.getId()));
    }
    @GetMapping("/api/v1/me/group")
    public ResponseEntity<GroupService.Group> own(Authentication auth, CsrfToken csrf) {
        csrf.getToken(); return response(service.own(auth));
    }
    @GetMapping("/api/v1/me/group-invitations")
    public ResponseEntity<List<GroupService.Invitation>> incoming(Authentication auth, CsrfToken csrf) {
        csrf.getToken(); return response(service.incoming(auth));
    }
    @PostMapping("/api/v1/groups")
    public ResponseEntity<GroupService.Group> create(Authentication auth) { return response(service.create(auth)); }
    @PostMapping("/api/v1/groups/join")
    public ResponseEntity<GroupService.Group> join(Authentication auth, @Valid @RequestBody Join body) { return response(service.join(auth, body.code())); }
    @PostMapping("/api/v1/groups/{id}/code")
    public ResponseEntity<GroupService.Group> code(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Code body) {
        return response(service.code(auth, id, body.expectedRevision(), body.enabled()));
    }
    @PostMapping("/api/v1/groups/{id}/invitations")
    public ResponseEntity<GroupService.Group> invite(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Invite body) {
        return response(service.invite(auth, id, body.expectedRevision(), body.battleTag()));
    }
    @PostMapping("/api/v1/me/group-invitations/{id}/accept")
    public ResponseEntity<GroupService.Group> accept(Authentication auth, @PathVariable UUID id) { return response(service.accept(auth, id)); }
    @PostMapping("/api/v1/me/group-invitations/{id}/decline")
    public ResponseEntity<Void> decline(Authentication auth, @PathVariable UUID id) { service.decline(auth, id); return response(null); }
    @PostMapping("/api/v1/groups/{id}/invitations/{invitationId}/cancel")
    public ResponseEntity<GroupService.Group> cancel(Authentication auth, @PathVariable UUID id, @PathVariable UUID invitationId, @Valid @RequestBody Revision body) {
        return response(service.cancelInvitation(auth, id, body.expectedRevision(), invitationId));
    }
    @PostMapping("/api/v1/groups/{id}/captain")
    public ResponseEntity<GroupService.Group> transfer(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Target body) {
        return response(service.transfer(auth, id, body.expectedRevision(), body.accountId()));
    }
    @PostMapping("/api/v1/groups/{id}/members/remove")
    public ResponseEntity<GroupService.Group> remove(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Target body) {
        return response(service.remove(auth, id, body.expectedRevision(), body.accountId()));
    }
    @PostMapping("/api/v1/groups/{id}/role")
    public ResponseEntity<GroupService.Group> role(Authentication auth, @PathVariable UUID id, @Valid @RequestBody DisplayRole body) {
        return response(service.displayRole(auth, id, body.expectedRoleVersion(), body.role()));
    }
    @PostMapping("/api/v1/groups/{id}/readiness")
    public ResponseEntity<GroupService.Group> ready(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Ready body) {
        return response(service.readiness(auth, id, body.expectedRevision(), body.confirmed(), body.seasonId(), body.seasonVersion(), body.selectionVersion()));
    }
    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
