package io.rbgs.api.evidence;

import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.identity.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static io.rbgs.api.evidence.CaptureJson.*;

@Service
public class CaptureService {
    private final JdbcTemplate db;
    private final CaptureJson json;
    private final AccountService accounts;
    private final boolean enabled;
    private final AccountRepository accountRepository;
    private final CaptureTestRepository tests;
    private final CaptureAssignmentRepository assignments;
    private static final SecureRandom RANDOM=new SecureRandom();
    public CaptureService(JdbcTemplate db,CaptureJson json,AccountService accounts,AccountRepository accountRepository,CaptureTestRepository tests,CaptureAssignmentRepository assignments,@Value("${rbgs.capture.controlled-enabled:false}") boolean enabled){this.db=db;this.json=json;this.accounts=accounts;this.accountRepository=accountRepository;this.tests=tests;this.assignments=assignments;this.enabled=enabled;}
    public record Receipt(int schemaVersion,UUID evidenceId,UUID assignmentId,UUID captureId,int captureRevision,
                          Instant receivedAt,boolean durablyStored,String processingStatus,String contentSha256) {}
    private void enabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Test matches are currently unavailable");}
    private UUID moderator(Authentication auth) {
        var account = accounts.currentUser(auth);
        if (account == null || !"MODERATOR".equals(account.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var persisted = accountRepository.findById(account.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!"ACTIVE".equals(persisted.getStatus()) || !"MODERATOR".equals(persisted.getRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return account.id();
    }


    @Transactional
    public Map<String,Object> create(Authentication auth,Map<String,Object> request) {
        enabled();
        UUID actor = moderator(auth);
        fields(request, "armMinutes roster", "testKind");
        CaptureTestKind kind;
        try { kind = request.containsKey("testKind") ? CaptureTestKind.valueOf(text(request.get("testKind"))) : CaptureTestKind.ROSTER_CONTROLLED; }
        catch (IllegalArgumentException error) { throw invalid(); }
        boolean captureOnly = kind == CaptureTestKind.CAPTURE_ONLY;
        long minutes = number(request.get("armMinutes"));
        if (minutes < 5 || minutes > 120) throw invalid();
        List<?> requestedRows = rows(request.get("roster"), captureOnly ? 1 : 2, captureOnly ? 1 : 40);
        List<Map<String,Object>> roster = new ArrayList<>();
        List<Map<String,Object>> reporters = new ArrayList<>();
        Set<UUID> owners = new HashSet<>();
        Set<String> teams = new HashSet<>();
        for (Object item : requestedRows) {
            var row = object(item);
            fields(row, "accountId character teamKey", "");
            identity(row.get("character"));
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
        var match = tests.saveAndFlush(new CaptureTestEntity(actor, now, deadline, json.encode(roster), kind));
        List<Map<String,Object>> issuedAssignments = new ArrayList<>();
        for (var reporter : reporters) {
            var assignment = assignments.saveAndFlush(new CaptureAssignmentEntity(match, (UUID)reporter.get("account"), (UUID)reporter.get("character"), (String)reporter.get("team")));
            issuedAssignments.add(Map.of("assignmentId", assignment.getId(), "captureId", assignment.getCaptureId(), "accountId", assignment.getAccountId()));
        }
        return Map.of("matchId", match.getId(), "mode", "CONTROLLED_TEST", "testKind", kind.name(), "armUntil", deadline, "assignments", issuedAssignments);
    }
    @Transactional
    public void cancel(Authentication auth,UUID match){enabled();moderator(auth);if(db.update("UPDATE rbgs.capture_test_matches SET cancelled_at=COALESCE(cancelled_at,?) WHERE id=?",Timestamp.from(Instant.now()),match)==0)throw notFound();}
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(UUID account) {
        enabled();
        List<Map<String,Object>> result = new ArrayList<>();
        for (var assignment : assignments.listOwn(account, PageRequest.of(0, 50))) {
            var test = assignment.getTestMatch();
            var view = new LinkedHashMap<String,Object>();
            view.put("captureId", assignment.getCaptureId());
            view.put("assignmentId", assignment.getId());
            view.put("armUntil", test.getArmUntil());
            view.put("codeIssued", assignment.getIssuedAt() != null);
            view.put("cancelled", test.getCancelledAt() != null);
            view.put("testKind", test.getTestKind().name());
            for (Object row : rows(json.parse(("{\"rows\":" + test.getExpectedRoster() + "}").getBytes(StandardCharsets.UTF_8)).get("rows"), 1, 40)) {
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
        enabled();
        var assignment = owned(account, capture, true);
        var test = assignment.getTestMatch();
        if (test.getTestKind() == CaptureTestKind.CAPTURE_ONLY && !captureOnlySupported) throw new ResponseStatusException(HttpStatus.CONFLICT, "Update the companion before getting a command for this test match");
        if (assignment.getCodeHash() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "The match command was already created. If you no longer have it, request a new test match.");
        if (test.getCancelledAt() != null || !Instant.now().isBefore(test.getArmUntil())) throw new ResponseStatusException(HttpStatus.GONE, "This test match is no longer available. Request a new one.");
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 16; i++) code.append(alphabet.charAt(RANDOM.nextInt(32)));
        assignment.issue(sha(code.toString().getBytes(StandardCharsets.UTF_8)), Instant.now());
        assignments.saveAndFlush(assignment);
        return Map.of("assignment", descriptor(account, capture), "assignmentCode", code.toString());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> descriptor(UUID account,UUID capture) {
        enabled();
        var a = owned(account, capture, false);
        var test = a.getTestMatch();
        if (a.getCodeHash() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Get your match command in the companion first");
        var result = new LinkedHashMap<String,Object>();
        result.put("schemaVersion", 2); result.put("matchId", test.getId()); result.put("assignmentId", a.getId()); result.put("captureId", a.getCaptureId());
        result.put("assignmentCodeSha256", a.getCodeHash()); result.put("mode", "CONTROLLED_TEST"); result.put("issuedAt", a.getIssuedAt()); result.put("armUntil", test.getArmUntil());
        result.put("rosterVersion", 1); result.put("assignedCharacterId", a.getCharacterId()); result.put("assignedTeamKey", a.getAssignedTeam()); result.put("expectedMapProfile", test.getProfile());
        result.put("expectedRoster", json.parse(("{\"rows\":" + test.getExpectedRoster() + "}").getBytes(StandardCharsets.UTF_8)).get("rows"));
        if (test.getTestKind() == CaptureTestKind.CAPTURE_ONLY) result.put("testKind", "CAPTURE_ONLY");
        return result;
    }
    private CaptureAssignmentEntity owned(UUID account,UUID capture,boolean lock) {
        return (lock ? assignments.lockOwn(account, capture) : assignments.findOwn(account, capture)).orElseThrow(CaptureService::notFound);
    }
    @Transactional
    public Receipt ingest(DeviceService.Status reporter,Map<String,Object> request) {
        enabled();json.evidence(request);UUID capture=uuid(request.get("captureId"));var a=owned(reporter.accountId(),capture,true);
        if(!uuid(request.get("assignmentId")).equals(a.getId())||!uuid(request.get("matchId")).equals(a.getTestMatch().getId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This saved data does not belong to the assigned match");
        String codeHash=sha(text(request.get("assignmentCode")).getBytes(StandardCharsets.UTF_8));if(!codeHash.equals(a.getCodeHash()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The command in this saved data does not match the assigned match");
        int revision=(int)number(request.get("captureRevision"));String contentHash=sha(json.canonical(request));
        var previous=db.queryForList("SELECT * FROM rbgs.capture_evidence WHERE account_id=? AND assignment_id=? AND capture_id=? AND revision=?",reporter.accountId(),a.getId(),capture,revision);
        if(!previous.isEmpty()){if(!contentHash.equals(previous.getFirst().get("content_sha256")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Different data was already uploaded for this saved version. Your earlier upload has been kept.");return receipt(previous.getFirst());}
        // Never store plaintext correlators server-side. Preserve all observations
        // unchanged in a redacted immutable report, with both submitted/stored hashes.
        var redacted=new LinkedHashMap<>(request);redacted.remove("assignmentCode");redacted.put("assignmentCodeSha256",codeHash);String stored=json.encode(redacted);
        UUID id=UUID.randomUUID();Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);String initialReason=a.getTestMatch().getCancelledAt()!=null?"ASSIGNMENT_CANCELLED":now.isAfter(a.getTestMatch().getArmUntil())?"LATE_CLIENT_TIMING_UNVERIFIED":a.getTestMatch().getTestKind()==CaptureTestKind.CAPTURE_ONLY?"CAPTURE_ONLY_NO_EXPECTED_TEAMS":"IDENTITY_AND_ROSTER_MAPPING_UNVERIFIED";
        db.update("INSERT INTO rbgs.capture_evidence(id,account_id,device_id,assignment_id,capture_id,revision,received_at,content_sha256,stored_sha256,redacted_payload,processing_status,review_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,reporter.accountId(),reporter.deviceId(),a.getId(),capture,revision,Timestamp.from(now),contentHash,sha(stored.getBytes(StandardCharsets.UTF_8)),stored,"PENDING",initialReason);
        db.update("INSERT INTO rbgs.capture_processing_jobs(evidence_id,created_at) VALUES(?,?)",id,Timestamp.from(now));
        return new Receipt(2,id,(UUID)a.getId(),capture,revision,now,true,"PENDING",contentHash);
    }
    @Transactional(readOnly=true)
    public Receipt recover(UUID account,UUID assignment,UUID capture,int revision) {
        enabled();var rows=db.queryForList("SELECT * FROM rbgs.capture_evidence WHERE account_id=? AND assignment_id=? AND capture_id=? AND revision=?",account,assignment,capture,revision);
        if(rows.isEmpty())throw notFound();return receipt(rows.getFirst());
    }
    private static Receipt receipt(Map<String,Object> row){return new Receipt(2,(UUID)row.get("id"),(UUID)row.get("assignment_id"),(UUID)row.get("capture_id"),((Number)row.get("revision")).intValue(),instant(row,"received_at"),true,(String)row.get("processing_status"),(String)row.get("content_sha256"));}
    private static Instant instant(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
    private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"No assigned match or upload confirmation was found for this account");}
    @Scheduled(fixedDelayString="${rbgs.capture.processing-delay-ms:2000}", initialDelayString="${rbgs.capture.processing-delay-ms:2000}")
    @Transactional
    public void processPending() {
        if(!enabled)return;
        var jobs=db.queryForList("SELECT j.evidence_id,e.redacted_payload,e.stored_sha256 FROM rbgs.capture_processing_jobs j JOIN rbgs.capture_evidence e ON e.id=j.evidence_id ORDER BY j.created_at LIMIT 25 FOR UPDATE OF j SKIP LOCKED");
        for(var job:jobs){boolean intact=sha(((String)job.get("redacted_payload")).getBytes(StandardCharsets.UTF_8)).equals(job.get("stored_sha256"));
            db.update("UPDATE rbgs.capture_evidence SET processing_status='REVIEW',processed_at=?,review_reason=CASE WHEN ? THEN review_reason ELSE 'STORED_DATA_INTEGRITY' END WHERE id=?",Timestamp.from(Instant.now()),intact,job.get("evidence_id"));
            db.update("DELETE FROM rbgs.capture_processing_jobs WHERE evidence_id=?",job.get("evidence_id"));}
    }
}
