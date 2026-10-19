package io.rbgs.api.evidence;

import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.identity.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"rbgs.capture.controlled-enabled=true","rbgs.capture.processing-delay-ms=3600000"})
@AutoConfigureMockMvc
class CaptureTests {
 @Autowired CaptureService service;@Autowired CaptureJson json;@Autowired DeviceService devices;@Autowired AccountService identities;@Autowired JdbcTemplate db;@Autowired MockMvc mvc;
 String subject;Account owner;List<UUID> matches;static final String ISSUER="https://idp.example";
 @BeforeEach void setup(){subject=UUID.randomUUID().toString();owner=identities.upsert(ISSUER,subject,"CaptureFixture");db.update("UPDATE rbgs.accounts SET account_role='MODERATOR' WHERE id=?",owner.id());matches=new ArrayList<>();}
 @AfterEach void cleanup(){for(UUID id:matches){db.update("DELETE FROM rbgs.capture_processing_jobs WHERE evidence_id IN (SELECT id FROM rbgs.capture_evidence WHERE assignment_id IN (SELECT id FROM rbgs.capture_assignments WHERE match_id=?))",id);
  db.update("DELETE FROM rbgs.capture_evidence WHERE assignment_id IN (SELECT id FROM rbgs.capture_assignments WHERE match_id=?)",id);db.update("DELETE FROM rbgs.capture_assignments WHERE match_id=?",id);db.update("DELETE FROM rbgs.capture_test_matches WHERE id=?",id);}
  db.update("DELETE FROM rbgs.device_pairings WHERE account_id=?",owner.id());db.update("DELETE FROM rbgs.devices WHERE account_id=?",owner.id());db.update("DELETE FROM rbgs.accounts WHERE id=?",owner.id());}
 OAuth2AuthenticationToken auth(){Instant now=Instant.now();var id=new OidcIdToken("fixture",now,now.plusSeconds(300),Map.of("iss",ISSUER,"sub",subject));var a=List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));return new OAuth2AuthenticationToken(new DefaultOidcUser(a,id),a,"battle-net");}
 Map<String,Object> request(){var own=new LinkedHashMap<String,Object>();own.put("accountId",owner.id().toString());own.put("character",Map.of("name","FixturePlayer","realm","FixtureRealm"));own.put("teamKey","A");
  var other=new LinkedHashMap<String,Object>();other.put("accountId",null);other.put("character",Map.of("name","Opponent","realm","FixtureRealm"));other.put("teamKey","B");return Map.of("armMinutes",15,"roster",List.of(own,other));}
 @Test void companion_wire_is_accepted_without_rewriting_observations()throws Exception {
  byte[] bytes=getClass().getResourceAsStream("/capture/evidence-wire.sanitized.json").readAllBytes();var request=json.parse(bytes);json.evidence(request);assertArrayEquals(bytes,json.canonical(request));
 }
 @Test void future_patch_metadata_is_persisted_idempotently_and_still_goes_to_review()throws Exception {
  UUID capture=create();var payload=payload(capture,service.issue(owner.id(),capture));var client=CaptureJson.object(payload.get("client"));
  client.put("gameVersion","1.61.0");client.put("gameBuild","100001");client.put("interface",16100);client.put("adapterVersion","darkspear-scoreboard-v1");
  var reporter=devices.authorize("Bearer "+token(true).token(),"evidence:write");var receipt=service.ingest(reporter,payload);
  assertTrue(receipt.durablyStored());assertEquals(receipt.evidenceId(),service.ingest(reporter,payload).evidenceId());
  String stored=db.queryForObject("SELECT redacted_payload FROM rbgs.capture_evidence WHERE id=?",String.class,receipt.evidenceId());
  var storedClient=CaptureJson.object(json.parse(stored.getBytes(StandardCharsets.UTF_8)).get("client"));assertEquals("100001",storedClient.get("gameBuild"));assertEquals("1.61.0",storedClient.get("gameVersion"));assertEquals(16100L,CaptureJson.number(storedClient.get("interface")));
  service.processPending();assertEquals("REVIEW",service.recover(owner.id(),receipt.assignmentId(),capture,receipt.captureRevision()).processingStatus());
  client.put("gameBuild","100002");assertEquals(409,assertThrows(ResponseStatusException.class,()->service.ingest(reporter,payload)).getStatusCode().value());
 }
 UUID create(){var match=service.create(auth(),request());UUID id=(UUID)match.get("matchId");matches.add(id);return (UUID)CaptureJson.object(((List<?>)match.get("assignments")).getFirst()).get("captureId");}
 Map<String,Object> captureOnlyRequest(){var result=new LinkedHashMap<String,Object>(request());var own=new LinkedHashMap<>(CaptureJson.object(((List<?>)result.get("roster")).getFirst()));
  own.put("teamKey",null);result.put("roster",List.of(own));result.put("testKind","CAPTURE_ONLY");return result;}
 @Test void random_bg_capture_uses_one_own_reporter_no_declared_team_and_durable_review_only_receipts()throws Exception {
  var response=mvc.perform(post("/api/v1/moderation/capture-tests").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf()).contentType("application/json").content(json.encode(captureOnlyRequest())))
   .andExpect(status().isOk()).andExpect(jsonPath("$.testKind").value("CAPTURE_ONLY")).andReturn();
  var created=json.parse(response.getResponse().getContentAsByteArray());UUID match=CaptureJson.uuid(created.get("matchId"));matches.add(match);
  UUID capture=CaptureJson.uuid(CaptureJson.object(((List<?>)created.get("assignments")).getFirst()).get("captureId"));
  assertEquals("CAPTURE_ONLY",db.queryForObject("SELECT test_kind FROM rbgs.capture_test_matches WHERE id=?",String.class,match));
  assertNull(db.queryForObject("SELECT assigned_team FROM rbgs.capture_assignments WHERE capture_id=?",String.class,capture));
  assertEquals("CAPTURE_ONLY",service.list(owner.id()).getFirst().get("testKind"));assertTrue(service.list(UUID.randomUUID()).isEmpty());
  var issued=service.issue(owner.id(),capture,true);var descriptor=CaptureJson.object(issued.get("assignment"));assertEquals("CAPTURE_ONLY",descriptor.get("testKind"));assertNull(descriptor.get("assignedTeamKey"));
  var frozen=(List<?>)descriptor.get("expectedRoster");assertEquals(1,frozen.size());assertNull(CaptureJson.object(frozen.getFirst()).get("assignedTeamKey"));
  var reporter=devices.authorize("Bearer "+token(true).token(),"evidence:write");var payload=payload(capture,issued);var receipt=service.ingest(reporter,payload);
  assertTrue(receipt.durablyStored());assertEquals(receipt.evidenceId(),service.ingest(reporter,payload).evidenceId());service.processPending();
  assertEquals("REVIEW",service.recover(owner.id(),receipt.assignmentId(),capture,158).processingStatus());
  assertEquals("CAPTURE_ONLY_NO_EXPECTED_TEAMS",db.queryForObject("SELECT review_reason FROM rbgs.capture_evidence WHERE id=?",String.class,receipt.evidenceId()));
 }
 @Test void capture_only_rejects_other_accounts_declared_teams_extra_participants_and_unknown_kinds(){
  var request=captureOnlyRequest();var own=CaptureJson.object(((List<?>)request.get("roster")).getFirst());
  own.put("accountId",UUID.randomUUID().toString());assertEquals(422,assertThrows(ResponseStatusException.class,()->service.create(auth(),request)).getStatusCode().value());
  own.put("accountId",owner.id().toString());own.put("teamKey","A");assertEquals(422,assertThrows(ResponseStatusException.class,()->service.create(auth(),request)).getStatusCode().value());
  own.put("teamKey",null);request.put("roster",List.of(own,own));assertEquals(422,assertThrows(ResponseStatusException.class,()->service.create(auth(),request)).getStatusCode().value());
  request.put("roster",List.of(own));request.put("testKind","UNKNOWN");assertEquals(422,assertThrows(ResponseStatusException.class,()->service.create(auth(),request)).getStatusCode().value());
  request.remove("testKind");assertEquals(422,assertThrows(ResponseStatusException.class,()->service.create(auth(),request)).getStatusCode().value());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rbgs.capture_test_matches WHERE created_by=?",Integer.class,owner.id()));
 }
 @Test void old_companion_cannot_consume_a_capture_only_code_before_upgrading()throws Exception {
  var match=service.create(auth(),captureOnlyRequest());matches.add((UUID)match.get("matchId"));UUID capture=(UUID)CaptureJson.object(((List<?>)match.get("assignments")).getFirst()).get("captureId");
  var token=token(true);String endpoint="/api/v1/companion/assignments/"+capture+"/issue";
  mvc.perform(post(endpoint).header("Authorization","Bearer "+token.token())).andExpect(status().isConflict());
  assertNull(db.queryForObject("SELECT code_hash FROM rbgs.capture_assignments WHERE capture_id=?",String.class,capture));
  mvc.perform(post(endpoint).header("Authorization","Bearer "+token.token()).header("X-RBGS-Capture-Test-Kinds","CAPTURE_ONLY"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.assignment.testKind").value("CAPTURE_ONLY"));
 }
 DeviceService.Token token(boolean capture){var p=devices.start(capture?DeviceService.CAPTURE_SCOPE:DeviceService.SCOPE);devices.approve(auth(),p.code(),capture);return devices.exchange(p.pollingSecret());}
 Map<String,Object> payload(UUID capture,Map<String,Object> issued)throws Exception {
  byte[] fixture=getClass().getResourceAsStream("/capture/product-final.sanitized.json").readAllBytes();var root=json.parse(fixture);var result=new LinkedHashMap<>(CaptureJson.object(((List<?>)root.get("captures")).getFirst()));
  var descriptor=CaptureJson.object(issued.get("assignment"));result.put("schemaVersion",2);result.put("matchId",descriptor.get("matchId").toString());result.put("assignmentId",descriptor.get("assignmentId").toString());result.put("captureId",capture.toString());result.put("assignmentCode",issued.get("assignmentCode"));
  CaptureJson.object(result.get("client")).put("companionVersion","0.0.1");var c=CaptureJson.object(result.get("capture"));c.put("incompleteReason",null);
  for(Object row:(List<?>)c.get("roster"))CaptureJson.object(CaptureJson.object(row).get("character")).putIfAbsent("realm",null);return result;
 }
 @Test void old_scopes_do_not_upgrade_and_capture_permissions_require_explicit_approval(){var p=devices.start(DeviceService.CAPTURE_SCOPE);
  assertEquals(403,assertThrows(ResponseStatusException.class,()->devices.approve(auth(),p.code())).getStatusCode().value());devices.approve(auth(),p.code(),true);var fresh=devices.exchange(p.pollingSecret());assertEquals(DeviceService.CAPTURE_SCOPE,fresh.scope());
  var old=token(false);assertEquals(DeviceService.SCOPE,devices.status("Bearer "+old.token()).scope());assertEquals(403,assertThrows(ResponseStatusException.class,()->devices.authorize("Bearer "+old.token(),"evidence:write")).getStatusCode().value());}
 @Test void moderator_route_uses_fresh_role_csrf_and_never_device_credentials()throws Exception {
  String body=json.encode(request());mvc.perform(post("/api/v1/moderation/capture-tests").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).contentType("application/json").content(body)).andExpect(status().isForbidden());
  db.update("UPDATE rbgs.accounts SET account_role='USER' WHERE id=?",owner.id());mvc.perform(post("/api/v1/moderation/capture-tests").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
  var token=token(true);mvc.perform(post("/api/v1/moderation/capture-tests").header("Authorization","Bearer "+token.token()).contentType("application/json").content(body)).andExpect(status().isForbidden());
 }
 @Test void issuance_is_owner_only_once_and_hash_only() {UUID capture=create();var issue=service.issue(owner.id(),capture);String code=(String)issue.get("assignmentCode");assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{16}"));
  assertEquals(CaptureJson.sha(code.getBytes(StandardCharsets.UTF_8)),db.queryForObject("SELECT code_hash FROM rbgs.capture_assignments WHERE capture_id=?",String.class,capture));
  assertFalse(service.descriptor(owner.id(),capture).containsKey("assignmentCode"));assertEquals(409,assertThrows(ResponseStatusException.class,()->service.issue(owner.id(),capture)).getStatusCode().value());
  assertEquals(404,assertThrows(ResponseStatusException.class,()->service.descriptor(UUID.randomUUID(),capture)).getStatusCode().value());
 }
 @Test void idempotent_receipts_survive_lost_response_job_processing_and_second_device()throws Exception {
  UUID capture=create();var issued=service.issue(owner.id(),capture);var payload=payload(capture,issued);var t=token(true);var reporter=devices.authorize("Bearer "+t.token(),"evidence:write");
  var first=service.ingest(reporter,payload);assertTrue(first.durablyStored());assertEquals(CaptureJson.sha(json.canonical(payload)),first.contentSha256());
  var recovered=service.recover(owner.id(),first.assignmentId(),capture,first.captureRevision());assertEquals(first.evidenceId(),recovered.evidenceId());assertEquals(first.receivedAt(),recovered.receivedAt());
  String stored=db.queryForObject("SELECT redacted_payload FROM rbgs.capture_evidence WHERE id=?",String.class,first.evidenceId());assertFalse(stored.contains((String)issued.get("assignmentCode")));assertTrue(stored.contains("assignmentCodeSha256"));
  service.processPending();assertEquals("REVIEW",service.recover(owner.id(),first.assignmentId(),capture,first.captureRevision()).processingStatus());
  var other=token(true);assertEquals(first.evidenceId(),service.ingest(devices.authorize("Bearer "+other.token(),"evidence:write"),payload).evidenceId());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rbgs.capture_evidence WHERE capture_id=?",Integer.class,capture));
 }
 @Test void concurrent_submissions_commit_one_report_and_receipt()throws Exception {
  UUID capture=create();var payload=payload(capture,service.issue(owner.id(),capture));var reporter=devices.authorize("Bearer "+token(true).token(),"evidence:write");
  try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);Callable<CaptureService.Receipt> submit=()->{gate.await();return service.ingest(reporter,payload);};var a=pool.submit(submit);var b=pool.submit(submit);gate.countDown();assertEquals(a.get(15,TimeUnit.SECONDS).evidenceId(),b.get(15,TimeUnit.SECONDS).evidenceId());}
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rbgs.capture_evidence WHERE capture_id=?",Integer.class,capture));
 }
 @Test void changed_content_is_conflict_wrong_owner_code_and_binding_are_rejected()throws Exception {
  UUID capture=create();var payload=payload(capture,service.issue(owner.id(),capture));var reporter=devices.authorize("Bearer "+token(true).token(),"evidence:write");service.ingest(reporter,payload);
  var c=CaptureJson.object(payload.get("capture"));var first=CaptureJson.object(((List<?>)c.get("roster")).getFirst());first.put("damage",1);assertEquals(409,assertThrows(ResponseStatusException.class,()->service.ingest(reporter,payload)).getStatusCode().value());
  payload.put("assignmentCode","0123456789ABCDEF");assertEquals(403,assertThrows(ResponseStatusException.class,()->service.ingest(reporter,payload)).getStatusCode().value());
  assertEquals(404,assertThrows(ResponseStatusException.class,()->service.recover(UUID.randomUUID(),CaptureJson.uuid(payload.get("assignmentId")),capture,158)).getStatusCode().value());
 }
 @Test void bearer_scopes_cookie_separation_revocation_and_bounded_parsing_apply_to_http()throws Exception {
  UUID capture=create();var payload=payload(capture,service.issue(owner.id(),capture));String body=json.encode(payload);var legacy=token(false);
  mvc.perform(post("/api/v1/evidence").header("Authorization","Bearer "+legacy.token()).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/evidence").with(oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject))).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
  var t=token(true);mvc.perform(post("/api/v1/evidence").header("Authorization","Bearer "+t.token()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.durablyStored").value(true)).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(post("/api/v1/evidence").header("Authorization","Bearer "+t.token()).contentType("application/json").content("{\"schemaVersion\":2,\"schemaVersion\":2}")).andExpect(status().isUnprocessableContent());
  mvc.perform(post("/api/v1/evidence").header("Authorization","Bearer "+t.token()).contentType("application/json").content(" ".repeat(262145))).andExpect(status().isPayloadTooLarge());
  devices.revoke(auth(),t.deviceId());mvc.perform(get("/api/v1/companion/assignments").header("Authorization","Bearer "+t.token())).andExpect(status().isUnauthorized());
 }
 @Test void late_or_cancelled_reports_remain_review_evidence_not_ranked_results()throws Exception {
  UUID capture=create();var issued=service.issue(owner.id(),capture);service.cancel(auth(),(UUID)CaptureJson.object(issued.get("assignment")).get("matchId"));
  var receipt=service.ingest(devices.authorize("Bearer "+token(true).token(),"evidence:write"),payload(capture,issued));service.processPending();
  assertEquals("REVIEW",service.recover(owner.id(),receipt.assignmentId(),capture,158).processingStatus());assertEquals("ASSIGNMENT_CANCELLED",db.queryForObject("SELECT review_reason FROM rbgs.capture_evidence WHERE id=?",String.class,receipt.evidenceId()));
 }
 @Test void canonicalization_matches_native_golden_vector_and_rejects_invalid_shapes(){var value=json.parse("{\"z\":{\"x\":\"雪\",\"b\":1},\"a\":[null,\"é\",0]}".getBytes(StandardCharsets.UTF_8));assertEquals("3c0a12b024485c41eb2ca490f71a774d611cea485cb1babe8d4ea07e7361e10d",CaptureJson.sha(json.canonical(value)));
  assertThrows(ResponseStatusException.class,()->json.parse("{} {}".getBytes(StandardCharsets.UTF_8)));assertThrows(ResponseStatusException.class,()->json.canonical(Map.of("float",1.5)));
 }
}
