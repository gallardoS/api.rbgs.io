package io.rbgs.api.matches;

import io.rbgs.api.evidence.CaptureEvidenceRepository;
import io.rbgs.api.evidence.CaptureProcessingRepository;
import io.rbgs.api.evidence.CaptureJson;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MatchResultService {
    private final MatchRepository matches;
    private final MatchAssignmentRepository assignments;
    private final CaptureEvidenceRepository evidence;
    private final CaptureProcessingRepository jobs;
    private final MatchResultEventRepository events;
    private final MatchVerification verification;
    private final CaptureJson json;
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final MatchAvailability availability;
    private final MatchDisputeService disputes;

    public record Result(UUID matchId, String mode, String status, String reason, String outcome,
            String winningTeam, int reportsA, int reportsB, int requiredPerTeam, Instant deadline,
            long version, String policyVersion, boolean ratingApplied, String explanation, Instant resolvedAt) {}

    public record ModeratorDecision(Long expectedVersion, String outcome, String winningTeam, String reason, List<UUID> evidenceIds) {}

    @Transactional
    public Result decide(Authentication auth, UUID id, ModeratorDecision request) {
        UUID actor = moderator(auth);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.outcome() == null || !Set.of("WIN", "DRAW", "VOID").contains(request.outcome())
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 2000
                || request.reason().codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')
                || request.evidenceIds() == null || request.evidenceIds().size() > 1000
                || request.evidenceIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.evidenceIds()).size() != request.evidenceIds().size()
                || ("WIN".equals(request.outcome()) ? !("A".equals(request.winningTeam()) || "B".equals(request.winningTeam())) : request.winningTeam() != null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a decision, reason, evidence references and current version");
        var match = matches.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        availability.requireAvailable(match);
        if (match.getResultVersion() != request.expectedVersion() || match.getCancelledAt() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This match changed. Refresh before deciding.");
        var reports = evidence.findForMatch(id);
        if (reports.stream().anyMatch(e -> "PENDING".equals(e.getValidationStatus())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Evidence is still being processed. Refresh before deciding.");
        var selected = reports.stream().filter(e -> request.evidenceIds().contains(e.getId())).toList();
        if (selected.size() != request.evidenceIds().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evidence must belong to this match");
        if (!"VOID".equals(request.outcome())) {
            if (match.getEvidencePolicy() != MatchEvidencePolicy.FULL_ROSTER || selected.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A supported outcome requires full-roster evidence");
            for (var report : selected) {
                var checked = verification.normalize(match, report);
                if (!"VALID".equals(checked.status()) || !request.outcome().equals(checked.facts().get("outcome"))
                        || !Objects.equals(request.winningTeam(), checked.facts().get("winningTeam")))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected evidence does not support this outcome");
            }
        }
        String previous = match.getVerificationStatus(), previousReason = match.getVerificationReason();
        String previousOutcome = match.getOutcomeKind(), previousWinner = match.getWinningTeam();
        boolean correction = events.findByMatchIdOrderByRecordedAtAscIdAsc(id).stream()
                .anyMatch(e -> Set.of("VERIFIED", "RESOLVED", "VOIDED").contains(e.getStatus()));
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String selectedIds = json.encode(request.evidenceIds().stream().map(UUID::toString).sorted().toList());
        match.decision(request.reason().strip(), reportIds(reports), now);
        boolean voided = "VOID".equals(request.outcome());
        match.result(voided ? "VOIDED" : "RESOLVED", voided ? "MODERATOR_VOIDED" : "MODERATOR_RESOLVED",
                voided ? null : request.outcome(), request.winningTeam(), match.getReportsA(), match.getReportsB());
        events.save(new MatchResultEvent(match, previous, previousReason, previousOutcome, previousWinner, selectedIds,
                now, actor, request.expectedVersion(), correction));
        disputes.resolved(match, actor, now);
        matches.flush();
        return view(match);
    }

    private String reportIds(List<io.rbgs.api.evidence.CaptureEvidenceEntity> reports) {
        return json.encode(reports.stream().map(e -> e.getId().toString()).sorted().toList());
    }

    public record DeclaredPlayer(String name, String realm, String team) {}
    public record Summary(UUID matchId, MatchMode mode, MatchEvidencePolicy evidencePolicy, String profile,
            Instant createdAt, Instant captureStartDeadline, Instant cancelledAt, List<DeclaredPlayer> roster,
            Result result, boolean canCancel) {}
    public record Listing(List<Summary> items, String nextCursor) {}

    private UUID moderator(Authentication auth) {
        var account = accounts.currentUser(auth);
        if (account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (accountRepository.findById(account.id()).filter(a -> "ACTIVE".equals(a.getStatus()) && "MODERATOR".equals(a.getRole())).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return account.id();
    }

    @Transactional(readOnly = true)
    public Listing list(Authentication auth, String status, int limit, String cursor) {
        moderator(auth);
        if (!Set.of("ALL", "PENDING", "VERIFIED", "REVIEW", "RESOLVED", "VOIDED", "CANCELLED").contains(status) || limit < 1 || limit > 50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid match filter or page size");
        var page = PageRequest.of(0, limit + 1);
        List<MatchEntity> rows;
        if (cursor == null) rows = matches.listModeration(status, availability.controlledEnabled(), page);
        else {
            Instant before;
            UUID beforeId;
            try {
                if (cursor.length() > 256) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\n", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                before = Instant.parse(parts[0]);
                beforeId = CaptureJson.uuid(parts[1]);
            } catch (RuntimeException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid match cursor");
            }
            rows = matches.listModerationBefore(status, availability.controlledEnabled(), before, beforeId, page);
        }
        boolean more = rows.size() > limit;
        var visible = rows.subList(0, Math.min(rows.size(), limit));
        String next = null;
        if (more) {
            var last = visible.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.getCreatedAt() + "\n" + last.getId()).getBytes(StandardCharsets.UTF_8));
        }
        var summaries = visible.stream().map(m -> {
            var roster = verification.expectedRoster(m).stream().map(row -> {
                var character = CaptureJson.object(row.get("character"));
                return new DeclaredPlayer((String)character.get("name"), (String)character.get("realm"), (String)row.get("assignedTeamKey"));
            }).toList();
            return new Summary(m.getId(), m.getMode(), m.getEvidencePolicy(), m.getProfile(), m.getCreatedAt(),
                    m.getCaptureStartDeadline(), m.getCancelledAt(), roster, view(m),
                    m.getMode() == MatchMode.CONTROLLED_TEST && m.getCancelledAt() == null && m.getDecisionAt() == null && availability.controlledEnabled());
        }).toList();
        return new Listing(summaries, next);
    }

    @Transactional(readOnly = true)
    public Result result(Authentication auth, UUID id) {
        var account = accounts.currentUser(auth);
        if (account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var persisted = accountRepository.findById(account.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!"ACTIVE".equals(persisted.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!"MODERATOR".equals(persisted.getRole()) && !assignments.existsByMatchIdAndAccountId(id, account.id()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return view(matches.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public Result ownResult(UUID account, UUID capture) {
        return view(assignments.findOwn(account, capture).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).getMatch());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> review(Authentication auth, UUID id) {
        moderator(auth);
        var match = matches.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var reports = evidence.findForMatch(id).stream().map(e -> {
            var report = new LinkedHashMap<String, Object>();
            report.put("evidenceId", e.getId());
            report.put("accountId", e.getAccountId());
            report.put("revision", e.getRevision());
            report.put("receivedAt", e.getReceivedAt());
            report.put("validation", e.getValidationStatus());
            report.put("reason", e.getReviewReason());
            report.put("normalizedFacts", e.getNormalizedFacts());
            report.put("redactedPayload", e.getRedactedPayload());
            return report;
        }).toList();
        return Map.of("result", view(match), "expectedRoster", verification.expectedRoster(match),
                "reports", reports, "events", events.findByMatchIdOrderByRecordedAtAscIdAsc(id));
    }

    Result view(MatchEntity match) {
        availability.requireAvailable(match);
        return new Result(match.getId(), match.getMode().name(), match.getVerificationStatus(), match.getVerificationReason(),
                match.getOutcomeKind(), match.getWinningTeam(), match.getReportsA(), match.getReportsB(), MatchVerification.QUORUM,
                match.getVerificationDeadline(), match.getResultVersion(), MatchVerification.POLICY, false,
                Set.of("RESOLVED", "VOIDED").contains(match.getVerificationStatus()) ? match.getDecisionReason() : null,
                Set.of("RESOLVED", "VOIDED").contains(match.getVerificationStatus()) ? match.getDecisionAt() : null);
    }

    @Transactional
    @Scheduled(fixedDelayString="${rbgs.matches.processing-delay-ms:${rbgs.capture.processing-delay-ms:2000}}",
            initialDelayString="${rbgs.matches.processing-delay-ms:${rbgs.capture.processing-delay-ms:2000}}")
    public void processPending() {
        Instant now = Instant.now();
        for (var match : matches.lockWork(now, availability.controlledEnabled())) evaluate(match, now);
    }

    void evaluate(MatchEntity match, Instant now) {
        var reports = evidence.findForMatch(match.getId());
        for (var report : reports) {
            if ("PENDING".equals(report.getValidationStatus())) {
                var validation = verification.normalize(match, report);
                report.processed(validation.status(), validation.reason(), validation.facts() == null ? null : json.encode(validation.facts()), now);
            }
            if ("VALID".equals(report.getValidationStatus())) match.verificationDeadline(report.getReceivedAt().plus(MatchVerification.REPORT_WINDOW));
            if (jobs.existsById(report.getId())) jobs.deleteById(report.getId());
        }
        var decision = verification.aggregate(match, reports, now);
        if (match.getDecisionAt() != null && match.getCancelledAt() == null) {
            boolean newEvidence = !Objects.equals(match.getDecisionEvidenceIds(), reportIds(reports));
            if (newEvidence)
                decision = new MatchVerification.Decision("REVIEW", "LATE_EVIDENCE_AFTER_DECISION", null, null, decision.a(), decision.b());
            else
                decision = new MatchVerification.Decision(match.getVerificationStatus(), match.getVerificationReason(),
                        match.getOutcomeKind(), match.getWinningTeam(), decision.a(), decision.b());
        }
        String previous = match.getVerificationStatus();
        boolean changed = !previous.equals(decision.status()) || !Objects.equals(match.getVerificationReason(), decision.reason())
                || !Objects.equals(match.getOutcomeKind(), decision.outcome()) || !Objects.equals(match.getWinningTeam(), decision.winner());
        match.result(decision.status(), decision.reason(), decision.outcome(), decision.winner(), decision.a(), decision.b());
        if (changed) events.save(new MatchResultEvent(match, previous, json.encode(reports.stream().map(e -> e.getId().toString()).toList()), now));
    }
}
