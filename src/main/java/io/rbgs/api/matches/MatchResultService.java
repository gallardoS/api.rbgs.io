package io.rbgs.api.matches;

import io.rbgs.api.evidence.CaptureEvidenceRepository;
import io.rbgs.api.evidence.CaptureProcessingRepository;
import io.rbgs.api.evidence.CaptureJson;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import java.time.*;
import java.util.*;
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

    public record Result(UUID matchId, String mode, String status, String reason, String outcome,
            String winningTeam, int reportsA, int reportsB, int requiredPerTeam, Instant deadline,
            long version, String policyVersion, boolean ratingApplied) {}

    private void moderator(Authentication auth) {
        var account = accounts.currentUser(auth);
        if (account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (accountRepository.findById(account.id()).filter(a -> "ACTIVE".equals(a.getStatus()) && "MODERATOR".equals(a.getRole())).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
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

    private Result view(MatchEntity match) {
        availability.requireAvailable(match);
        return new Result(match.getId(), match.getMode().name(), match.getVerificationStatus(), match.getVerificationReason(),
                match.getOutcomeKind(), match.getWinningTeam(), match.getReportsA(), match.getReportsB(), MatchVerification.QUORUM,
                match.getVerificationDeadline(), match.getResultVersion(), MatchVerification.POLICY, false);
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
        String previous = match.getVerificationStatus();
        boolean changed = !previous.equals(decision.status()) || !Objects.equals(match.getVerificationReason(), decision.reason())
                || !Objects.equals(match.getOutcomeKind(), decision.outcome()) || !Objects.equals(match.getWinningTeam(), decision.winner());
        match.result(decision.status(), decision.reason(), decision.outcome(), decision.winner(), decision.a(), decision.b());
        if (changed) events.save(new MatchResultEvent(match, previous, json.encode(reports.stream().map(e -> e.getId().toString()).toList()), now));
    }
}
