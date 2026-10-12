package io.rbgs.api.evidence;

import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.identity.AccountService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
    private static final SecureRandom RANDOM=new SecureRandom();
    public CaptureService(JdbcTemplate db,CaptureJson json,AccountService accounts,@Value("${rbgs.capture.controlled-enabled:false}") boolean enabled){this.db=db;this.json=json;this.accounts=accounts;this.enabled=enabled;}
    public record Receipt(int schemaVersion,UUID evidenceId,UUID assignmentId,UUID captureId,int captureRevision,
                          Instant receivedAt,boolean durablyStored,String processingStatus,String contentSha256) {}
    private void enabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Controlled capture testing is disabled");}
    private UUID moderator(Authentication auth){var account=accounts.currentUser(auth);if(account==null||!"MODERATOR".equals(account.role())
            ||!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM rbgs.accounts WHERE id=? AND account_status='ACTIVE' AND account_role='MODERATOR')",Boolean.class,account.id())))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return account.id();}

    @Transactional
    public Map<String,Object> create(Authentication auth,Map<String,Object> request) {
        enabled();UUID actor=moderator(auth);fields(request,"armMinutes roster","");long minutes=number(request.get("armMinutes"));if(minutes<5||minutes>120)throw invalid();
        List<?> rows=rows(request.get("roster"),2,40);List<Map<String,Object>> roster=new ArrayList<>();List<Map<String,Object>> reporters=new ArrayList<>();Set<UUID> owners=new HashSet<>();Set<String> teams=new HashSet<>();
        for(Object item:rows){var row=object(item);fields(row,"accountId character teamKey","");identity(row.get("character"));String team=text(row.get("teamKey"));if(!Set.of("A","B").contains(team))throw invalid();teams.add(team);
            UUID character=UUID.randomUUID();var frozen=new LinkedHashMap<String,Object>();frozen.put("characterId",character.toString());frozen.put("character",row.get("character"));frozen.put("assignedTeamKey",team);roster.add(frozen);
            if(row.get("accountId")!=null){UUID owner=uuid(row.get("accountId"));if(!owners.add(owner))throw invalid();
                if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM rbgs.accounts WHERE id=? AND account_status='ACTIVE')",Boolean.class,owner)))throw invalid();
                reporters.add(Map.of("account",owner,"character",character,"team",team));}
        }
        if(teams.size()!=2||reporters.isEmpty())throw invalid();
        UUID match=UUID.randomUUID();Instant now=Instant.now(),deadline=now.plusSeconds(minutes*60);
        db.update("INSERT INTO rbgs.capture_test_matches(id,created_by,created_at,arm_until,expected_roster,profile) VALUES(?,?,?,?,?,?)",match,actor,Timestamp.from(now),Timestamp.from(deadline),json.encode(roster),"DARKSPEAR_DIAGNOSTIC");
        List<Map<String,Object>> assignments=new ArrayList<>();
        for(var reporter:reporters){UUID id=UUID.randomUUID(),capture=UUID.randomUUID();db.update("INSERT INTO rbgs.capture_assignments(id,match_id,account_id,capture_id,character_id,assigned_team) VALUES(?,?,?,?,?,?)",
                id,match,reporter.get("account"),capture,reporter.get("character"),reporter.get("team"));assignments.add(Map.of("assignmentId",id,"captureId",capture,"accountId",reporter.get("account")));}
        return Map.of("matchId",match,"mode","CONTROLLED_TEST","armUntil",deadline,"assignments",assignments);
    }
    @Transactional
    public void cancel(Authentication auth,UUID match){enabled();moderator(auth);if(db.update("UPDATE rbgs.capture_test_matches SET cancelled_at=COALESCE(cancelled_at,?) WHERE id=?",Timestamp.from(Instant.now()),match)==0)throw notFound();}
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(UUID account) {
        enabled();return db.query("SELECT a.capture_id,a.id,m.arm_until,a.issued_at,m.cancelled_at,m.expected_roster,a.character_id FROM rbgs.capture_assignments a JOIN rbgs.capture_test_matches m ON m.id=a.match_id WHERE a.account_id=? ORDER BY m.created_at DESC LIMIT 50",
                (r,i)->{var result=new LinkedHashMap<String,Object>();result.put("captureId",r.getObject(1,UUID.class));result.put("assignmentId",r.getObject(2,UUID.class));result.put("armUntil",r.getTimestamp(3).toInstant());result.put("codeIssued",r.getTimestamp(4)!=null);result.put("cancelled",r.getTimestamp(5)!=null);
                    var roster=json.parse(("{\"rows\":"+r.getString(6)+"}").getBytes(StandardCharsets.UTF_8));String character=r.getObject(7,UUID.class).toString();
                    for(Object row:rows(roster.get("rows"),2,40)){var entry=object(row);if(character.equals(entry.get("characterId")))result.put("character",entry.get("character"));}return result;},account);
    }
    @Transactional
    public Map<String,Object> issue(UUID account,UUID capture) {
        enabled();var assignment=owned(account,capture,true);
        if(assignment.get("code_hash")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Code already issued. A lost issuance requires a fresh controlled assignment.");
        if(assignment.get("cancelled_at")!=null||!Instant.now().isBefore(instant(assignment,"arm_until")))throw new ResponseStatusException(HttpStatus.GONE,"Assignment is no longer available for arming");
        String alphabet="0123456789ABCDEFGHJKMNPQRSTVWXYZ";StringBuilder code=new StringBuilder();for(int i=0;i<16;i++)code.append(alphabet.charAt(RANDOM.nextInt(32)));
        Instant issued=Instant.now();db.update("UPDATE rbgs.capture_assignments SET code_hash=?,issued_at=? WHERE id=?",sha(code.toString().getBytes(StandardCharsets.UTF_8)),Timestamp.from(issued),assignment.get("id"));
        return Map.of("assignment",descriptor(account,capture),"assignmentCode",code.toString());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> descriptor(UUID account,UUID capture) {
        enabled();var a=owned(account,capture,false);if(a.get("code_hash")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Assignment code has not been issued");
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",2);result.put("matchId",a.get("match_id"));result.put("assignmentId",a.get("id"));result.put("captureId",a.get("capture_id"));
        result.put("assignmentCodeSha256",a.get("code_hash"));result.put("mode","CONTROLLED_TEST");result.put("issuedAt",instant(a,"issued_at"));result.put("armUntil",instant(a,"arm_until"));
        result.put("rosterVersion",1);result.put("assignedCharacterId",a.get("character_id"));result.put("assignedTeamKey",a.get("assigned_team"));result.put("expectedMapProfile","DARKSPEAR_DIAGNOSTIC");
        result.put("expectedRoster",json.parse(("{\"rows\":"+a.get("expected_roster")+"}").getBytes(StandardCharsets.UTF_8)).get("rows"));return result;
    }
    private Map<String,Object> owned(UUID account,UUID capture,boolean lock) {
        var rows=db.queryForList("SELECT a.*,m.arm_until,m.expected_roster,m.cancelled_at FROM rbgs.capture_assignments a JOIN rbgs.capture_test_matches m ON m.id=a.match_id WHERE a.account_id=? AND a.capture_id=?"+(lock?" FOR UPDATE OF a,m":""),account,capture);
        if(rows.isEmpty())throw notFound();return rows.getFirst();
    }
    @Transactional
    public Receipt ingest(DeviceService.Status reporter,Map<String,Object> request) {
        enabled();json.evidence(request);UUID capture=uuid(request.get("captureId"));var a=owned(reporter.accountId(),capture,true);
        if(!uuid(request.get("assignmentId")).equals(a.get("id"))||!uuid(request.get("matchId")).equals(a.get("match_id")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Assignment binding mismatch");
        String codeHash=sha(text(request.get("assignmentCode")).getBytes(StandardCharsets.UTF_8));if(!codeHash.equals(a.get("code_hash")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Assignment correlator mismatch");
        int revision=(int)number(request.get("captureRevision"));String contentHash=sha(json.canonical(request));
        var previous=db.queryForList("SELECT * FROM rbgs.capture_evidence WHERE account_id=? AND assignment_id=? AND capture_id=? AND revision=?",reporter.accountId(),a.get("id"),capture,revision);
        if(!previous.isEmpty()){if(!contentHash.equals(previous.getFirst().get("content_sha256")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Content changed under the same capture revision");return receipt(previous.getFirst());}
        // Never store plaintext correlators server-side. Preserve all observations
        // unchanged in a redacted immutable report, with both submitted/stored hashes.
        var redacted=new LinkedHashMap<>(request);redacted.remove("assignmentCode");redacted.put("assignmentCodeSha256",codeHash);String stored=json.encode(redacted);
        UUID id=UUID.randomUUID();Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);String initialReason=a.get("cancelled_at")!=null?"ASSIGNMENT_CANCELLED":now.isAfter(instant(a,"arm_until"))?"LATE_CLIENT_TIMING_UNVERIFIED":"IDENTITY_AND_ROSTER_MAPPING_UNVERIFIED";
        db.update("INSERT INTO rbgs.capture_evidence(id,account_id,device_id,assignment_id,capture_id,revision,received_at,content_sha256,stored_sha256,redacted_payload,processing_status,review_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,reporter.accountId(),reporter.deviceId(),a.get("id"),capture,revision,Timestamp.from(now),contentHash,sha(stored.getBytes(StandardCharsets.UTF_8)),stored,"PENDING",initialReason);
        db.update("INSERT INTO rbgs.capture_processing_jobs(evidence_id,created_at) VALUES(?,?)",id,Timestamp.from(now));
        return new Receipt(2,id,(UUID)a.get("id"),capture,revision,now,true,"PENDING",contentHash);
    }
    @Transactional(readOnly=true)
    public Receipt recover(UUID account,UUID assignment,UUID capture,int revision) {
        enabled();var rows=db.queryForList("SELECT * FROM rbgs.capture_evidence WHERE account_id=? AND assignment_id=? AND capture_id=? AND revision=?",account,assignment,capture,revision);
        if(rows.isEmpty())throw notFound();return receipt(rows.getFirst());
    }
    private static Receipt receipt(Map<String,Object> row){return new Receipt(2,(UUID)row.get("id"),(UUID)row.get("assignment_id"),(UUID)row.get("capture_id"),((Number)row.get("revision")).intValue(),instant(row,"received_at"),true,(String)row.get("processing_status"),(String)row.get("content_sha256"));}
    private static Instant instant(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
    private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Own capture assignment or receipt not found");}
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
