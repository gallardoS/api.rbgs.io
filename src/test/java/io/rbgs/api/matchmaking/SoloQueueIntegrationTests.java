package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.identity.*;
import io.rbgs.api.matches.*;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SoloQueueIntegrationTests {
    @Autowired SoloQueueStore store;
    @Autowired QueueTicketRepository tickets;
    @Autowired QueueProposalRepository proposals;
    @Autowired AccountService accounts;
    @Autowired CharacterIdentityRepository identities;
    @Autowired SeasonSelectionRepository selections;
    @Autowired SeasonRepository seasons;
    @Autowired MatchRepository matches;
    @Autowired MatchRoomRepository rooms;
    @Autowired SoloQueueRooms roomService;
    @Autowired MatchAssignmentRepository assignments;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    private final List<SoloQueueCandidate> candidates = new ArrayList<>();
    private SeasonEntity season;
    private String subject;
    private static final String ISSUER = "https://soloq-fixture.example";

    @BeforeEach void setup() {
        season = seasons.saveAndFlush(new SeasonEntity("SoloQ fixture",SeasonKind.BETA,RatingSubjectType.ACCOUNT));
        var entered = Instant.now().minusSeconds(300);
        for (int i = 0; i < 20; i++) {
            String id = UUID.randomUUID().toString(); if (i == 0) subject = id;
            var account = accounts.upsert(ISSUER,id,"SoloFixture#"+i);
            var character = identities.saveAndFlush(CharacterIdentity.declared(account.id(),"Fixture"+i,"Realm"));
            candidates.add(new SoloQueueCandidate(account.id(),character.getId(),season.getId(),"EU","verified-fixture","fixture",
                    i < 2 ? MatchRole.FC : i < 8 ? MatchRole.HEALER : MatchRole.DPS,true,entered,1500,"class"+i%4,0));
            var selection = new SeasonSelection(account.id(),season.getId());
            selection.choose(character.getId(),RatingSubjectType.ACCOUNT,candidates.getLast().role(),true);
            selections.saveAndFlush(selection);
        }
    }
    @AfterEach void cleanup() {
        var ids = db.queryForList("SELECT DISTINCT proposal_id FROM rbgs.queue_tickets WHERE season_id=? AND proposal_id IS NOT NULL",UUID.class,season.getId());
        db.update("DELETE FROM rbgs.queue_tickets WHERE season_id=?",season.getId());
        for (var id : ids) { rooms.deleteById(id); proposals.deleteById(id); }
        for (var c : candidates) {
            db.update("DELETE FROM rbgs.season_selections WHERE account_id=?",c.accountId());
            db.update("DELETE FROM rbgs.character_identities WHERE account_id=?",c.accountId());
            db.update("DELETE FROM rbgs.accounts WHERE id=?",c.accountId());
        }
        seasons.deleteById(season.getId());
    }
    private void enqueue() { candidates.forEach(store::enqueueVerifiedSnapshot); }

    @Test void persistedSnapshotsSurviveReloadAndProposalReservesAllTwenty() {
        enqueue();
        var first = tickets.active(candidates.getFirst().accountId()).orElseThrow();
        assertEquals(candidates.getFirst(),first.candidate());
        assertEquals(first.getId(),store.enqueueVerifiedSnapshot(candidates.getFirst()).getId());
        var proposal = store.propose(Instant.now(),(a,b)->true).orElseThrow();
        var recovered = proposals.findById(proposal.getId()).orElseThrow().proposal();
        assertEquals(10,recovered.teamA().size()); assertEquals(10,recovered.teamB().size());
        for (var c : candidates) {
            var ticket = tickets.active(c.accountId()).orElseThrow();
            assertEquals("PROPOSED",ticket.getState()); assertEquals(proposal.getId(),ticket.getProposalId());
            assertThrows(IllegalStateException.class,()->store.cancel(c.accountId(),ticket.getId()));
        }
        assertTrue(store.propose(Instant.now(),(a,b)->true).isEmpty());
    }
    @Test void concurrentWorkersCannotCreateDuplicateProposals() throws Exception {
        enqueue();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(()-> { start.await(); return store.propose(Instant.now(),(x,y)->true); });
            var b = executor.submit(()-> { start.await(); return store.propose(Instant.now(),(x,y)->true); });
            start.countDown();
            assertEquals(1,(a.get(20,TimeUnit.SECONDS).isPresent()?1:0)+(b.get(20,TimeUnit.SECONDS).isPresent()?1:0));
        }
    }
    @Test void olderTicketsWithoutCaptainMetadataRecoverAStableRandomTieBreak() {
        var candidate=candidates.getFirst();var ticket=store.enqueueVerifiedSnapshot(candidate);
        db.update("UPDATE rbgs.queue_tickets SET snapshot=(snapshot::jsonb-'gamesPlayed'-'visibleRating'-'captainLottery')::text WHERE id=?",ticket.getId());
        var recovered=tickets.findById(ticket.getId()).orElseThrow().candidate();
        assertEquals(0,recovered.gamesPlayed());assertEquals(0,recovered.visibleRating());
        assertEquals(ticket.getId(),recovered.captainLottery());
        assertEquals(recovered,tickets.findById(ticket.getId()).orElseThrow().candidate());
    }
    @Test void cancellationRacesMatchingWithOneTerminalOutcome() throws Exception {
        enqueue(); var account = candidates.getFirst().accountId(); var id = tickets.active(account).orElseThrow().getId();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(()-> { start.await(); try {store.cancel(account,id); return true;} catch(IllegalStateException reserved) { return false;} });
            var match = executor.submit(()-> { start.await(); return store.propose(Instant.now(),(x,y)->true); });
            start.countDown();
            boolean cancelled = cancel.get(20,TimeUnit.SECONDS);
            var proposal = match.get(20,TimeUnit.SECONDS);
            assertEquals(cancelled ? "CANCELLED" : "PROPOSED",tickets.findById(id).orElseThrow().getState());
            assertEquals(!cancelled,proposal.isPresent());
        }
    }
    @Test void accountUniquenessAppliesAcrossQueueTypes() {
        var c = candidates.getFirst(); var ticket = store.enqueueVerifiedSnapshot(c);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()-> db.update(
                "INSERT INTO rbgs.queue_tickets(id,account_id,character_id,season_id,queue_type,state,entered_at,snapshot) VALUES (?,?,?,?,'PREMADE','QUEUED',CURRENT_TIMESTAMP,'{}')",
                UUID.randomUUID(),c.accountId(),c.characterId(),c.seasonId()));
        store.cancel(c.accountId(),ticket.getId());
        store.cancel(c.accountId(),ticket.getId());
        UUID premade = UUID.randomUUID();
        db.update("INSERT INTO rbgs.queue_tickets(id,account_id,character_id,season_id,queue_type,state,entered_at,snapshot) VALUES (?,?,?,?,'PREMADE','QUEUED',CURRENT_TIMESTAMP,'{}')",
                premade,c.accountId(),c.characterId(),c.seasonId());
        assertThrows(IllegalStateException.class,()->store.enqueueVerifiedSnapshot(c));
        assertThrows(NoSuchElementException.class,()->store.cancel(c.accountId(),premade));
        db.update("DELETE FROM rbgs.queue_tickets WHERE id=?",premade);
        assertNotEquals(ticket.getId(),store.enqueueVerifiedSnapshot(c).getId());
    }
    @Test void accountSuspensionIsRevalidatedBeforeProposal() {
        enqueue();
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?",candidates.getFirst().accountId());
        assertTrue(store.propose(Instant.now(),(a,b)->true).isEmpty());
        assertTrue(tickets.active(candidates.getFirst().accountId()).isEmpty());
    }
    @Test void endedCompetitionReleasesReviewWithoutChangingResult() {
        var c = candidates.getFirst(); var now = Instant.now();
        var match = matches.saveAndFlush(new MatchEntity(c.accountId(),now,now.plusSeconds(600),"[]",MatchMode.MATCHMADE,
                MatchEvidencePolicy.FULL_ROSTER,"DARKSPEAR_DIAGNOSTIC",now.plusSeconds(1200)));
        try {
            assignments.saveAndFlush(new MatchAssignmentEntity(match,c.accountId(),c.characterId(),"A"));
            match.result("REVIEW","INSUFFICIENT_REPORTS",null,null,0,0); match = matches.saveAndFlush(match);
            assertTrue(assignments.hasBlockingParticipation(c.accountId(),Instant.now()));
            assertThrows(IllegalStateException.class,()->store.enqueueVerifiedSnapshot(c));
            match.endCompetition(now.plusSeconds(1)); matches.saveAndFlush(match);
            assertFalse(assignments.hasBlockingParticipation(c.accountId(),Instant.now()));
            assertNotNull(store.enqueueVerifiedSnapshot(c));
            assertEquals("REVIEW",matches.findById(match.getId()).orElseThrow().getVerificationStatus());
        } finally { db.update("DELETE FROM rbgs.match_assignments WHERE match_id=?",match.getId()); matches.deleteById(match.getId()); }
    }
    @Test void changedSelectionIsCancelledBeforeReservation() {
        enqueue(); var c = candidates.getFirst();
        var selection = selections.findByAccountIdAndSeasonId(c.accountId(),season.getId()).orElseThrow();
        selection.choose(c.characterId(),RatingSubjectType.ACCOUNT,c.role(),false);
        selections.saveAndFlush(selection);
        assertTrue(store.propose(Instant.now(),(a,b)->true).isEmpty());
        assertFalse(tickets.hasReservation(c.accountId()));
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor login() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)));
    }
    @Test void publicEntryIsDeniedAndDoesNotCreateTicketAndStatusIsPrivate() throws Exception {
        mvc.perform(get("/api/v1/local/soloq")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/solo-queue")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/solo-queue").with(login())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.ticket").isEmpty());
        mvc.perform(post("/api/v1/me/solo-queue").with(login())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/solo-queue").with(login()).with(csrf()).contentType("application/json").content("{\"seasonId\":\"00000000-0000-0000-0000-000000000001\",\"seasonVersion\":0,\"selectionVersion\":0}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers").value(org.hamcrest.Matchers.hasItem("FOREVER_COMPATIBILITY_UNVERIFIED")));
        assertFalse(tickets.hasReservation(candidates.getFirst().accountId()));
    }
    @Test void cancellationCannotAddressAnotherAccountsTicket() throws Exception {
        var own = store.enqueueVerifiedSnapshot(candidates.get(1));
        mvc.perform(delete("/api/v1/me/solo-queue/"+own.getId()).with(login()).with(csrf())).andExpect(status().isNotFound());
        assertTrue(tickets.hasReservation(candidates.get(1).accountId()));
    }
    @Test void allAcceptancesOpenPreparationAndDuplicateAcceptanceIsIdempotent() throws Exception {
        enqueue(); var proposal=store.propose(Instant.now(),(a,b)->true).orElseThrow();
        UUID room=proposal.getId();
        try (var executor=Executors.newFixedThreadPool(4)) {
            var tasks=candidates.stream().map(c->executor.submit(()->roomService.respondAt(c.accountId(),room,true,Instant.now()))).toList();
            for (var task:tasks) task.get(20,TimeUnit.SECONDS);
        }
        var view=roomService.own(candidates.getFirst().accountId(),room);
        assertEquals("PREPARING",view.status()); assertEquals("SIMULATION",view.source());
        assertTrue(view.teamA().stream().allMatch(SoloQueueRooms.Member::accepted));
        assertTrue(view.teamB().stream().allMatch(SoloQueueRooms.Member::accepted));
        assertNotNull(view.preparationDeadline()); long version=view.version();
        roomService.respondAt(candidates.getFirst().accountId(),room,true,Instant.now());
        assertEquals(version,roomService.own(candidates.getFirst().accountId(),room).version());
        assertThrows(NoSuchElementException.class,()->roomService.own(UUID.randomUUID(),room));
        roomService.expire(view.preparationDeadline());
        assertEquals("CANCELLED",roomService.own(candidates.getFirst().accountId(),room).status());
        for (var c:candidates) assertFalse(tickets.hasReservation(c.accountId()));
    }
    @Test void onlyAPendingParticipantCanDeclineAndReleaseTheRoom() {
        enqueue(); var proposal=store.propose(Instant.now(),(a,b)->true).orElseThrow();
        var c=candidates.getFirst();
        roomService.respondAt(c.accountId(),proposal.getId(),true,Instant.now());
        var accepted=roomService.own(c.accountId(),proposal.getId());
        var conflict=assertThrows(ResponseStatusException.class,
                () -> roomService.respondAt(c.accountId(),proposal.getId(),false,Instant.now()));
        assertEquals(HttpStatus.CONFLICT,conflict.getStatusCode());
        assertEquals(accepted,roomService.own(c.accountId(),proposal.getId()));
        for (var candidate:candidates) assertTrue(tickets.hasReservation(candidate.accountId()));
        roomService.respondAt(candidates.get(1).accountId(),proposal.getId(),false,Instant.now());
        var room=roomService.own(c.accountId(),proposal.getId());
        assertEquals("CANCELLED",room.status()); assertEquals("PARTICIPANT_DECLINED",room.cancellationReason());
        assertEquals(20,room.teamA().size()+room.teamB().size());
        for (var candidate:candidates) assertFalse(tickets.hasReservation(candidate.accountId()));
        assertNotNull(store.enqueueVerifiedSnapshot(c));
    }
    @Test void acceptanceAtTheDeadlineCancelsInsteadOfEnteringPreparation() {
        enqueue(); var proposal=store.propose(Instant.now(),(a,b)->true).orElseThrow();
        var c=candidates.getFirst(); var initial=roomService.own(c.accountId(),proposal.getId());
        roomService.respondAt(c.accountId(),proposal.getId(),true,initial.acceptanceDeadline());
        var result=roomService.own(c.accountId(),proposal.getId());
        assertEquals("CANCELLED",result.status()); assertEquals("DEADLINE_EXPIRED",result.cancellationReason());
        assertTrue(result.teamA().stream().noneMatch(SoloQueueRooms.Member::accepted));
        assertTrue(result.teamB().stream().noneMatch(SoloQueueRooms.Member::accepted));
    }
}
