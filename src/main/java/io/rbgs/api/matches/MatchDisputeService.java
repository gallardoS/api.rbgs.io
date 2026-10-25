package io.rbgs.api.matches;

import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.evidence.CaptureJson;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MatchDisputeService {
    private final MatchRepository matches;
    private final MatchAssignmentRepository assignments;
    private final MatchDisputeRepository disputes;
    private final MatchDisputeMessageRepository messages;
    private final MatchResultEventRepository events;
    private final MatchHistoryReadRepository reads;
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final MatchAvailability availability;
    private final CaptureJson json;

    public record Write(Long expectedVersion, String message) {}
    public record Message(UUID id, String author, String kind, String body, Instant at) {}
    public record Thread(UUID id, String status, long version, Instant openedAt, Instant updatedAt,
            boolean unread, List<Message> messages, boolean olderMessages) {}
    public record ModeratorThread(UUID id, String playerName, Thread thread) {}

    UUID activeAccount(Authentication auth, boolean moderator) {
        var account = accounts.currentUser(auth);
        if (account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var persisted = accountRepository.findById(account.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!"ACTIVE".equals(persisted.getStatus()) || (moderator && !"MODERATOR".equals(persisted.getRole())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return account.id();
    }

    MatchAssignmentEntity participant(UUID account, UUID id) {
        var assignment = assignments.findParticipant(id, account).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        availability.requireAvailable(assignment.getMatch());
        return assignment;
    }

    static Instant now() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }

    private String validate(Write request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.message() == null || request.message().isBlank() || request.message().length() > 2000
                || request.message().codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r'))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a message of 1–2000 characters and the current version");
        return request.message().strip();
    }

    private MatchEntity locked(UUID id) {
        var match = matches.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        availability.requireAvailable(match);
        return match;
    }

    private void writable(MatchEntity match) {
        if (match.getCancelledAt() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled tests cannot be disputed");
    }

    private void rateLimit(UUID thread, UUID actor, Instant at) {
        if (messages.countByDisputeIdAndAuthorIdAndRecordedAtAfter(thread, actor, at.minusSeconds(900)) >= 10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many messages. Try again later.");
    }

    @Transactional
    public Thread open(Authentication auth, UUID matchId, Write request) {
        UUID actor = activeAccount(auth, false);
        String body = validate(request);
        // Always lock the match before its conversation to serialize against decisions and uploads.
        var match = locked(matchId);
        participant(actor, matchId);
        writable(match);
        if (match.getResultVersion() != request.expectedVersion() || "PENDING".equals(match.getVerificationStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh the result before opening a dispute");
        Instant at = now();
        var existing = disputes.findByMatchIdAndAccountId(matchId, actor);
        if (existing.isPresent() && !"RESOLVED".equals(existing.get().getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use the existing dispute conversation");
        var thread = existing.orElseGet(() -> new MatchDispute(matchId, actor, match.getResultVersion(), at));
        rateLimit(thread.getId(), actor, at);
        String previous = match.getVerificationStatus(), previousReason = match.getVerificationReason();
        match.result("REVIEW", "PLAYER_DISPUTE", null, null, match.getReportsA(), match.getReportsB());
        if (!"REVIEW".equals(previous) || !"PLAYER_DISPUTE".equals(previousReason))
            events.save(new MatchResultEvent(match, previous, "[]", at));
        matches.flush();
        thread.reopen(match.getResultVersion(), at);
        disputes.saveAndFlush(thread);
        messages.saveAndFlush(new MatchDisputeMessage(thread.getId(), actor, "PLAYER", "DISPUTE_OPENED", body, at));
        return view(thread);
    }

    @Transactional
    public Thread reply(Authentication auth, UUID matchId, Write request) {
        UUID actor = activeAccount(auth, false);
        String body = validate(request);
        var match = locked(matchId);
        participant(actor, matchId);
        writable(match);
        var thread = disputes.findByMatchIdAndAccountId(matchId, actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (thread.getVersion() != request.expectedVersion() || "RESOLVED".equals(thread.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh this dispute before replying");
        Instant at = now(); rateLimit(thread.getId(), actor, at);
        thread.playerReply(at);
        messages.saveAndFlush(new MatchDisputeMessage(thread.getId(), actor, "PLAYER", "PLAYER_REPLY", body, at));
        disputes.flush();
        return view(thread);
    }

    @Transactional(readOnly = true)
    public List<ModeratorThread> moderation(Authentication auth, UUID matchId) {
        activeAccount(auth, true);
        var match = matches.findById(matchId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        availability.requireAvailable(match);
        return disputes.findByMatchIdOrderByOpenedAtAscIdAsc(matchId).stream().map(thread -> {
            String name = assignments.findParticipant(matchId, thread.getAccountId()).map(a -> declaredName(a)).orElse("Player");
            return new ModeratorThread(thread.getId(), name, view(thread));
        }).toList();
    }

    private String declaredName(MatchAssignmentEntity assignment) {
        // Only the assigned character's display name is exposed, never account display data.
        try {
            var rows = CaptureJson.rows(json.parse(("{\"rows\":" + assignment.getMatch().getExpectedRoster() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("rows"), 1, 40);
            for (var value : rows) {
                var row = CaptureJson.object(value);
                if (assignment.getCharacterId().toString().equals(row.get("characterId")))
                    return (String)CaptureJson.object(row.get("character")).get("name");
            }
        } catch (ResponseStatusException | ClassCastException invalid) { /* Preserve access to historical conversations. */ }
        return "Player";
    }

    @Transactional
    public Thread requestEvidence(Authentication auth, UUID matchId, UUID disputeId, Write request) {
        UUID actor = activeAccount(auth, true);
        String body = validate(request);
        var match = locked(matchId); writable(match);
        var thread = disputes.findById(disputeId).filter(d -> d.getMatchId().equals(matchId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (thread.getVersion() != request.expectedVersion() || "RESOLVED".equals(thread.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh this dispute before requesting evidence");
        Instant at = now(); rateLimit(disputeId, actor, at);
        thread.requestEvidence(at);
        messages.saveAndFlush(new MatchDisputeMessage(disputeId, actor, "MODERATOR", "EVIDENCE_REQUEST", body, at));
        disputes.flush(); return view(thread);
    }

    // Called inside the same locked-match transaction as the final moderator decision.
    void resolved(MatchEntity match, UUID actor, Instant at) {
        for (var thread : disputes.findByMatchIdOrderByOpenedAtAscIdAsc(match.getId())) {
            thread.resolve(at);
            messages.save(new MatchDisputeMessage(thread.getId(), actor, "SYSTEM", "DECISION", match.getDecisionReason(), at));
        }
    }

    void cancelled(MatchEntity match, UUID actor, Instant at) {
        for (var thread : disputes.findByMatchIdOrderByOpenedAtAscIdAsc(match.getId())) {
            thread.resolve(at);
            messages.save(new MatchDisputeMessage(thread.getId(), actor, "SYSTEM", "CANCELLED", "", at));
        }
    }

    @Transactional
    public void markRead(Authentication auth, UUID matchId, long expectedVersion, Long disputeVersion) {
        UUID actor = activeAccount(auth, false);
        var match = locked(matchId);
        var assignment = participant(actor, matchId);
        if (match.getResultVersion() != expectedVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh before marking updates read");
        var thread = disputes.findByMatchIdAndAccountId(matchId, actor).orElse(null);
        if (thread != null && (disputeVersion == null || thread.getVersion() != disputeVersion))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh the conversation before marking updates read");
        reads.save(new MatchHistoryRead(assignment.getId(), expectedVersion));
        if (thread != null) thread.seen(now());
    }

    Thread view(MatchDispute thread) {
        var rows = messages.findByDisputeIdOrderByRecordedAtDescIdDesc(thread.getId(), PageRequest.of(0, 101));
        var visible = new ArrayList<>(rows.subList(0, Math.min(rows.size(), 100)));
        Collections.reverse(visible);
        return new Thread(thread.getId(), thread.getStatus(), thread.getVersion(), thread.getOpenedAt(), thread.getUpdatedAt(), thread.unread(),
                visible.stream().map(m -> new Message(m.getId(), m.getAuthorRole(), m.getKind(), m.getBody(), m.getRecordedAt())).toList(), rows.size() > 100);
    }
}
