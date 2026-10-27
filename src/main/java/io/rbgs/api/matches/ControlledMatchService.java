package io.rbgs.api.matches;

import static io.rbgs.api.evidence.CaptureJson.*;
import io.rbgs.api.evidence.CaptureJson;
import io.rbgs.api.evidence.CaptureProfile;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ControlledMatchService {
    private final MatchRepository matches;
    private final MatchAssignmentRepository assignments;
    private final MatchResultService results;
    private final CaptureJson json;
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final MatchAvailability availability;
    private final MatchDisputeService disputes;

    private void enabled() { availability.requireControlledEnabled(); }
    private UUID moderator(Authentication auth) {
        var account = accounts.currentUser(auth);
        if (account == null || !"MODERATOR".equals(account.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var persisted = accountRepository.findById(account.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!"ACTIVE".equals(persisted.getStatus()) || !"MODERATOR".equals(persisted.getRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return account.id();
    }


    @Transactional
    public Map<String,Object> create(Authentication auth,Map<String,Object> request) {
        return create(auth, request, null);
    }

    public record SelectedTestCharacter(String namespace, long wowAccountId, long realmId, long characterId,
            String name, String realm) {}

    @Transactional
    public Map<String,Object> createAnniversary(Authentication auth, long armMinutes, SelectedTestCharacter selected) {
        UUID actor = moderator(auth);
        var row = new LinkedHashMap<String,Object>();
        row.put("accountId", actor.toString()); row.put("teamKey", null);
        row.put("character", Map.of("name", selected.name(), "realm", selected.realm()));
        return create(auth, Map.of("armMinutes", armMinutes, "roster", List.of(row), "testKind", "CAPTURE_ONLY",
                "expectedMapProfile", CaptureProfile.TBC_ANNIVERSARY_WSG_TEST.name()), selected);
    }

    private Map<String,Object> create(Authentication auth,Map<String,Object> request,SelectedTestCharacter selected) {
        enabled();
        UUID actor = moderator(auth);
        fields(request, "armMinutes roster", "testKind expectedMapProfile");
        ControlledMatchKind kind;
        try { kind = request.containsKey("testKind") ? ControlledMatchKind.valueOf(text(request.get("testKind"))) : ControlledMatchKind.ROSTER_CONTROLLED; }
        catch (IllegalArgumentException error) { throw invalid(); }
        boolean captureOnly = kind == ControlledMatchKind.CAPTURE_ONLY;
        var profile = request.containsKey("expectedMapProfile")
                ? CaptureProfile.find(text(request.get("expectedMapProfile"))).orElseThrow(CaptureJson::invalid)
                : CaptureProfile.DARKSPEAR_DIAGNOSTIC;
        // Temporary substitute-client observations cannot establish verified outcomes.
        if (profile == CaptureProfile.TBC_ANNIVERSARY_WSG_TEST && !captureOnly) throw invalid();
        if (profile == CaptureProfile.TBC_ANNIVERSARY_WSG_TEST && selected == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Select an owned Anniversary character before creating this test");
        long minutes = number(request.get("armMinutes"));
        if (minutes < 5 || minutes > 120) throw invalid();
        List<?> requestedRows = rows(request.get("roster"), captureOnly ? 1 : 2, captureOnly ? 1 : 40);
        List<Map<String,Object>> roster = new ArrayList<>();
        List<Map<String,Object>> reporters = new ArrayList<>();
        Set<UUID> owners = new HashSet<>();
        Set<String> teams = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Object item : requestedRows) {
            var row = object(item);
            fields(row, "accountId character teamKey", "");
            identity(row.get("character"));
            if (!names.add(MatchVerification.normalizedName(object(row.get("character")).get("name")))) throw invalid();
            String team = captureOnly ? null : text(row.get("teamKey"));
            if (captureOnly) {
                if (row.get("teamKey") != null || !actor.equals(uuid(row.get("accountId")))) throw invalid();
            } else {
                if (!Set.of("A", "B").contains(team)) throw invalid();
                teams.add(team);
            }
            UUID character = UUID.randomUUID();
            var frozen = new LinkedHashMap<String,Object>();
            frozen.put("characterId", character.toString());
            frozen.put("character", row.get("character"));
            frozen.put("assignedTeamKey", team);
            if (selected != null) frozen.put("providerReference", Map.of("namespace", selected.namespace(),
                    "wowAccountId", selected.wowAccountId(), "realmId", selected.realmId(), "characterId", selected.characterId()));
            roster.add(frozen);
            if (row.get("accountId") != null) {
                UUID owner = uuid(row.get("accountId"));
                if (!owners.add(owner)) throw invalid();
                if (accountRepository.findById(owner).filter(a -> "ACTIVE".equals(a.getStatus())).isEmpty()) throw invalid();
                var reporter = new LinkedHashMap<String,Object>();
                reporter.put("account", owner);
                reporter.put("character", character);
                reporter.put("team", team);
                reporters.add(reporter);
            }
        }
        if ((!captureOnly && teams.size() != 2) || reporters.isEmpty()) throw invalid();
        Instant now = Instant.now(), deadline = now.plusSeconds(minutes * 60);
        var match = matches.saveAndFlush(new MatchEntity(actor, now, deadline, json.encode(roster), MatchMode.CONTROLLED_TEST, kind.evidencePolicy(),
                profile.name(), deadline.plus(profile.maxDuration()).plus(MatchVerification.REPORT_WINDOW)));
        List<Map<String,Object>> issuedAssignments = new ArrayList<>();
        for (var reporter : reporters) {
            var assignment = assignments.saveAndFlush(new MatchAssignmentEntity(match, (UUID)reporter.get("account"), (UUID)reporter.get("character"), (String)reporter.get("team")));
            issuedAssignments.add(Map.of("assignmentId", assignment.getId(), "captureId", assignment.getCaptureId(), "accountId", assignment.getAccountId()));
        }
        return Map.of("matchId", match.getId(), "mode", "CONTROLLED_TEST", "testKind", kind.name(), "armUntil", deadline, "assignments", issuedAssignments);
    }
    @Transactional
    public void cancel(Authentication auth, UUID id) {
        cancel(auth, id, null);
    }

    @Transactional
    public void cancel(Authentication auth, UUID id, Long expectedVersion) {
        enabled();
        UUID actor = moderator(auth);
        var match = matches.lockById(id).filter(m -> m.getMode() == MatchMode.CONTROLLED_TEST)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (expectedVersion != null && expectedVersion < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (match.getCancelledAt() != null) return;
        if (match.getDecisionAt() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use a new moderator decision to correct a resolved match.");
        if (expectedVersion != null && expectedVersion != match.getResultVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This match changed. Refresh it before cancelling.");
        match.cancel(Instant.now());
        results.evaluate(match, Instant.now());
        disputes.cancelled(match, actor, MatchDisputeService.now());
    }
}
