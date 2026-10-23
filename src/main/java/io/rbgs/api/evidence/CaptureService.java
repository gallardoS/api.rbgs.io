package io.rbgs.api.evidence;

import static io.rbgs.api.evidence.CaptureJson.*;
import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.matches.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CaptureService {
    private final CaptureEvidenceRepository evidence;
    private final CaptureProcessingRepository jobs;
    private final CaptureJson json;
    private final MatchAssignmentRepository assignments;
    private final MatchAvailability availability;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Receipt(int schemaVersion, UUID evidenceId, UUID assignmentId, UUID captureId, int captureRevision,
            Instant receivedAt, boolean durablyStored, String processingStatus, String contentSha256) {}

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(UUID account) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (var assignment : assignments.listOwn(account, availability.controlledEnabled(), PageRequest.of(0, 50))) {
            var match = assignment.getMatch();
            var view = new LinkedHashMap<String,Object>();
            view.put("captureId", assignment.getCaptureId());
            view.put("assignmentId", assignment.getId());
            view.put("armUntil", match.getCaptureStartDeadline());
            view.put("codeIssued", assignment.getIssuedAt() != null);
            view.put("cancelled", match.getCancelledAt() != null);
            if (match.getMode() == MatchMode.CONTROLLED_TEST) view.put("testKind", match.getEvidencePolicy() == MatchEvidencePolicy.CAPTURE_ONLY ? "CAPTURE_ONLY" : "ROSTER_CONTROLLED");
            for (Object row : rows(json.parse(("{\"rows\":" + match.getExpectedRoster() + "}").getBytes(StandardCharsets.UTF_8)).get("rows"), 1, 40)) {
                var entry = object(row);
                if (assignment.getCharacterId().toString().equals(entry.get("characterId"))) view.put("character", entry.get("character"));
            }
            result.add(view);
        }
        return result;
    }
    @Transactional
    public Map<String,Object> issue(UUID account,UUID capture) {
        return issue(account, capture, false);
    }
    @Transactional
    public Map<String,Object> issue(UUID account,UUID capture,boolean captureOnlySupported) {
        var assignment = owned(account, capture, true);
        var match = assignment.getMatch();
        if (match.getEvidencePolicy() == MatchEvidencePolicy.CAPTURE_ONLY && !captureOnlySupported) throw new ResponseStatusException(HttpStatus.CONFLICT, "Update the companion before getting a command for this match");
        if (assignment.getCodeHash() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "The match command was already created. If you no longer have it, request a new match.");
        if (match.getCancelledAt() != null || !Instant.now().isBefore(match.getCaptureStartDeadline())) throw new ResponseStatusException(HttpStatus.GONE, "This match is no longer available. Request a new one.");
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 16; i++) code.append(alphabet.charAt(RANDOM.nextInt(32)));
        assignment.issue(sha(code.toString().getBytes(StandardCharsets.UTF_8)), Instant.now());
        assignments.saveAndFlush(assignment);
        return Map.of("assignment", descriptor(account, capture), "assignmentCode", code.toString());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> descriptor(UUID account,UUID capture) {
        var a = owned(account, capture, false);
        var match = a.getMatch();
        if (a.getCodeHash() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Get your match command in the companion first");
        var result = new LinkedHashMap<String,Object>();
        result.put("schemaVersion", 2); result.put("matchId", match.getId()); result.put("assignmentId", a.getId()); result.put("captureId", a.getCaptureId());
        result.put("assignmentCodeSha256", a.getCodeHash()); result.put("mode", match.getMode().name()); result.put("issuedAt", a.getIssuedAt()); result.put("armUntil", match.getCaptureStartDeadline());
        result.put("rosterVersion", 1); result.put("assignedCharacterId", a.getCharacterId()); result.put("assignedTeamKey", a.getAssignedTeam()); result.put("expectedMapProfile", match.getProfile());
        result.put("expectedRoster", json.parse(("{\"rows\":" + match.getExpectedRoster() + "}").getBytes(StandardCharsets.UTF_8)).get("rows"));
        if (match.getEvidencePolicy() == MatchEvidencePolicy.CAPTURE_ONLY) result.put("testKind", "CAPTURE_ONLY");
        return result;
    }
    private MatchAssignmentEntity owned(UUID account,UUID capture,boolean lock) {
        var assignment = (lock ? assignments.lockOwn(account, capture) : assignments.findOwn(account, capture)).orElseThrow(CaptureService::notFound);
        availability.requireAvailable(assignment.getMatch());
        return assignment;
    }
    @Transactional
    public Receipt ingest(DeviceService.Status reporter,Map<String,Object> request) {
        json.evidence(request);UUID capture=uuid(request.get("captureId"));var a=owned(reporter.accountId(),capture,true);
        if(!uuid(request.get("assignmentId")).equals(a.getId())||!uuid(request.get("matchId")).equals(a.getMatch().getId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This saved data does not belong to the assigned match");
        String codeHash=sha(text(request.get("assignmentCode")).getBytes(StandardCharsets.UTF_8));if(!codeHash.equals(a.getCodeHash()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The command in this saved data does not match the assigned match");
        int revision=(int)number(request.get("captureRevision"));String contentHash=sha(json.canonical(request));
        var previous=evidence.findByAccountIdAndAssignmentIdAndCaptureIdAndRevision(reporter.accountId(),a.getId(),capture,revision);
        if(previous.isPresent()){if(!contentHash.equals(previous.get().getContentSha256()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Different data was already uploaded for this saved version. Your earlier upload has been kept.");return receipt(previous.get());}
        // Never store plaintext correlators server-side. Preserve all observations
        // unchanged in a redacted immutable report, with both submitted/stored hashes.
        var redacted=new LinkedHashMap<>(request);redacted.remove("assignmentCode");redacted.put("assignmentCodeSha256",codeHash);String stored=json.encode(redacted);
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);String initialReason=a.getMatch().getCancelledAt()!=null?"ASSIGNMENT_CANCELLED":now.isAfter(a.getMatch().getCaptureStartDeadline())?"LATE_CLIENT_TIMING_UNVERIFIED":a.getMatch().getEvidencePolicy()==MatchEvidencePolicy.CAPTURE_ONLY?"CAPTURE_ONLY_NO_EXPECTED_TEAMS":"IDENTITY_AND_ROSTER_MAPPING_UNVERIFIED";
        var report=evidence.saveAndFlush(new CaptureEvidenceEntity(reporter.accountId(),reporter.deviceId(),a,revision,now,contentHash,sha(stored.getBytes(StandardCharsets.UTF_8)),stored,initialReason));
        jobs.saveAndFlush(new CaptureProcessingJob(report.getId(),now));
        return receipt(report);
    }
    @Transactional(readOnly=true)
    public Receipt recover(UUID account,UUID assignment,UUID capture,int revision) {
        var report=evidence.findByAccountIdAndAssignmentIdAndCaptureIdAndRevision(account,assignment,capture,revision).orElseThrow(CaptureService::notFound);
        availability.requireAvailable(report.getAssignment().getMatch());
        return receipt(report);
    }
    private static Receipt receipt(CaptureEvidenceEntity row){return new Receipt(2,row.getId(),row.getAssignment().getId(),row.getCaptureId(),row.getRevision(),row.getReceivedAt(),true,row.getProcessingStatus(),row.getContentSha256());}
    private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"No assigned match or upload confirmation was found for this account");}
}
