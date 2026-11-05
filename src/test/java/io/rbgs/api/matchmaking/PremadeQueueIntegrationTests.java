package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.groups.*;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.matches.MatchRoomRepository;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
class PremadeQueueIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired PremadeQueueStore store;
    @Autowired SoloQueueStore solo;
    @Autowired SoloQueueRooms roomService;
    @Autowired PremadeQueueEntryRepository entries;
    @Autowired QueueTicketRepository tickets;
    @Autowired QueueProposalRepository proposals;
    @Autowired MatchRoomRepository rooms;
    @Autowired PremadeGroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired SeasonRepository seasons;
    @Autowired SeasonSelectionRepository selections;
    @Autowired CharacterIdentityRepository characters;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    private SeasonEntity season;
    private final List<PremadeQueueCandidate> snapshots=new ArrayList<>();
    private final List<UUID> createdAccounts=new ArrayList<>();

    @BeforeEach void setup() {
        season=seasons.saveAndFlush(new SeasonEntity("Premade fixture",SeasonKind.BETA,RatingSubjectType.ACCOUNT));
        createGroup();createGroup();
    }
    private void createGroup() {
        var players=new ArrayList<SoloQueueCandidate>();var now=Instant.now().minusSeconds(60);
        for(int i=0;i<10;i++) {
            var account=accounts.upsert("https://premade-fixture.example",UUID.randomUUID().toString(),"PremadeFixture#"+i);
            createdAccounts.add(account.id());
            db.update("INSERT INTO rbgs.play_characters(account_id,namespace,wow_account_id,realm_id,character_id,region,name,realm) VALUES (?,'profile-forever-eu',1,1,1,'EU','Fixture','Realm')",account.id());
            var character=characters.saveAndFlush(CharacterIdentity.declared(account.id(),"Premade"+i,"Realm"));
            var choice=new SeasonSelection(account.id(),season.getId());choice.choose(character.getId(),RatingSubjectType.ACCOUNT,MatchRole.DPS,false);
            selections.saveAndFlush(choice);
            players.add(new SoloQueueCandidate(account.id(),character.getId(),season.getId(),"EU","premade-fixture","fixture",
                    MatchRole.DPS,false,now,1600,"class",0));
        }
        var group=new PremadeGroup(players.getFirst().accountId(),"EU");
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            groups.saveAndFlush(group);players.forEach(c->members.save(new GroupMember(c.accountId(),group.getId())));members.flush();
        });
        snapshots.add(new PremadeQueueCandidate(group.getId(),group.getRevision(),group.getCaptainId(),RatingSubjectType.ACCOUNT,players));
    }
    @AfterEach void cleanup() {
        var proposalIds=db.queryForList("SELECT DISTINCT proposal_id FROM rbgs.queue_tickets WHERE season_id=? AND proposal_id IS NOT NULL",UUID.class,season.getId());
        db.update("DELETE FROM rbgs.queue_tickets WHERE season_id=?",season.getId());
        snapshots.forEach(s->db.update("DELETE FROM rbgs.premade_queue_entries WHERE group_id=?",s.groupId()));
        proposalIds.forEach(id->{rooms.deleteById(id);proposals.deleteById(id);});
        for(var s:snapshots) {
            groups.deleteById(s.groupId());
        }
        for(var id:createdAccounts) {
            db.update("DELETE FROM rbgs.season_selections WHERE account_id=?",id);
            db.update("DELETE FROM rbgs.character_identities WHERE account_id=?",id);
            db.update("DELETE FROM rbgs.accounts WHERE id=?",id);
        }
        seasons.deleteById(season.getId());
    }
    private PremadeQueueEntry enqueue(PremadeQueueCandidate s) { return store.enqueueVerifiedSnapshot(s.captainId(),s); }
    private Optional<QueueProposal> propose() { return store.propose(Instant.now(),(r,a,b)->true,w->100); }

    @Test void immutableSnapshotsReserveTwentyAndUseSharedIndividualAcceptance() {
        var a=snapshots.getFirst();var entry=enqueue(a);enqueue(snapshots.getLast());
        assertEquals(entry.getId(),enqueue(a).getId());
        assertEquals(a,entries.findById(entry.getId()).orElseThrow().candidate());
        var proposal=propose().orElseThrow();
        assertEquals(20,tickets.findByProposalIdOrderByAccountId(proposal.getId()).size());
        assertNull(proposals.findById(proposal.getId()).orElseThrow().proposal().composition());
        for(var s:snapshots)for(var c:s.members())roomService.respond(c.accountId(),proposal.getId(),true);
        assertEquals("PREPARING",rooms.findById(proposal.getId()).orElseThrow().getStatus());
        roomService.cancelRoom(proposal.getId(),"FIXTURE_COMPLETE");
        for(var s:snapshots)for(var c:s.members())assertFalse(tickets.hasReservation(c.accountId()));
        assertTrue(propose().isEmpty());
    }
    @Test void captainOnlyControlAndAtomicAdmissionAgainstSoloReservations() {
        var s=snapshots.getFirst();
        assertThrows(SecurityException.class,()->store.enqueueVerifiedSnapshot(s.members().stream().filter(c->!c.accountId().equals(s.captainId())).findFirst().orElseThrow().accountId(),s));
        var entry=enqueue(s);
        assertThrows(SecurityException.class,()->store.cancel(snapshots.getLast().captainId(),entry.getId()));
        assertThrows(IllegalStateException.class,()->solo.enqueueVerifiedSnapshot(s.members().getFirst()));
        store.cancel(s.captainId(),entry.getId());store.cancel(s.captainId(),entry.getId());
        assertTrue(tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).stream().allMatch(t->t.getState().equals("CANCELLED")));
        var other=snapshots.getLast();var player=other.members().stream().filter(c->!c.accountId().equals(other.captainId())).findFirst().orElseThrow();
        db.update("DELETE FROM rbgs.group_members WHERE account_id=?",player.accountId());
        solo.enqueueVerifiedSnapshot(player);
        db.update("INSERT INTO rbgs.group_members(account_id,group_id) VALUES (?,?)",player.accountId(),other.groupId());
        assertThrows(IllegalStateException.class,()->enqueue(other));
        assertTrue(entries.findByGroupIdAndState(other.groupId(),"QUEUED").isEmpty());
        assertEquals(1,tickets.findBySeasonId(season.getId()).stream().filter(t->t.getState().equals("QUEUED")).count());
    }
    @Test void changedRosterCancelsWholeGroupBeforeReservation() {
        var s=snapshots.getFirst();var entry=enqueue(s);enqueue(snapshots.getLast());
        var victim=s.members().stream().filter(c->!c.accountId().equals(s.captainId())).findFirst().orElseThrow();
        db.update("DELETE FROM rbgs.group_members WHERE account_id=?",victim.accountId());
        db.update("UPDATE rbgs.premade_groups SET revision=revision+1 WHERE id=?",s.groupId());
        assertTrue(propose().isEmpty());assertEquals("CANCELLED",entries.findById(entry.getId()).orElseThrow().getState());
        for(var c:s.members())assertFalse(tickets.hasReservation(c.accountId()));
    }
    @Test void soloAndDisplayRoleChangesDoNotInvalidatePremades() {
        var s=snapshots.getFirst();var player=s.members().getFirst();
        enqueue(s);enqueue(snapshots.getLast());
        db.update("UPDATE rbgs.group_members SET display_role='FC',display_role_version=display_role_version+1 WHERE account_id=?",player.accountId());
        var selection=selections.findByAccountIdAndSeasonId(player.accountId(),season.getId()).orElseThrow();
        selection.choose(player.characterId(),RatingSubjectType.ACCOUNT,MatchRole.HEALER,true);selections.saveAndFlush(selection);
        assertTrue(propose().isPresent());
    }
    @Test void changedCharacterCancelsWholeGroupWithoutChangingSnapshot() {
        var s=snapshots.getFirst();var player=s.members().getFirst();var entry=enqueue(s);enqueue(snapshots.getLast());
        var replacement=characters.saveAndFlush(CharacterIdentity.declared(player.accountId(),"Replacement","Realm"));
        var selection=selections.findByAccountIdAndSeasonId(player.accountId(),season.getId()).orElseThrow();
        selection.choose(replacement.getId(),RatingSubjectType.ACCOUNT,MatchRole.DPS,false);selections.saveAndFlush(selection);
        assertTrue(propose().isEmpty());
        assertEquals(s,entries.findById(entry.getId()).orElseThrow().candidate());
        for(var c:s.members())assertFalse(tickets.hasReservation(c.accountId()));
    }
    @Test void tenPremadesPersistFiveDisjointRooms() {
        for(int i=0;i<8;i++)createGroup();snapshots.forEach(this::enqueue);
        var used=new HashSet<UUID>();
        for(int i=0;i<5;i++) {
            var proposal=propose().orElseThrow();
            var roster=tickets.findByProposalIdOrderByAccountId(proposal.getId());assertEquals(20,roster.size());
            roster.forEach(t->assertTrue(used.add(t.getAccountId())));
        }
        assertEquals(100,used.size());assertTrue(propose().isEmpty());
    }
    @Test void suspendedParticipantCancelsAllTenReservations() {
        var s=snapshots.getFirst();enqueue(s);enqueue(snapshots.getLast());
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?",s.members().getFirst().accountId());
        assertTrue(propose().isEmpty());for(var c:s.members())assertFalse(tickets.hasReservation(c.accountId()));
    }
    @Test void concurrentWorkersAndCancellationHaveOneTerminalOutcome() throws Exception {
        var s=snapshots.getFirst();var entry=enqueue(s);enqueue(snapshots.getLast());var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(3)) {
            var first=executor.submit(()->{start.await();return propose();});
            var second=executor.submit(()->{start.await();return propose();});
            var cancelled=executor.submit(()->{start.await();try{store.cancel(s.captainId(),entry.getId());return true;}catch(IllegalStateException reserved){return false;}});
            start.countDown();int count=(first.get(20,TimeUnit.SECONDS).isPresent()?1:0)+(second.get(20,TimeUnit.SECONDS).isPresent()?1:0);
            assertEquals(cancelled.get(20,TimeUnit.SECONDS)?0:1,count);
            assertEquals(count==1?"PROPOSED":"CANCELLED",entries.findById(entry.getId()).orElseThrow().getState());
        }
    }
    @Test void soloWorkerNeverConsumesPremadeTickets() {
        snapshots.forEach(this::enqueue);
        assertTrue(solo.propose(Instant.now(),(a,b)->true).isEmpty());
        assertEquals(20,tickets.findBySeasonId(season.getId()).stream().filter(t->t.getState().equals("QUEUED")).count());
        assertTrue(propose().isPresent());
    }
    @Test void rosterMutationRacingReservationCannotChangeAssignedParticipants() throws Exception {
        var s=snapshots.getFirst();enqueue(s);enqueue(snapshots.getLast());
        var victim=s.members().stream().filter(c->!c.accountId().equals(s.captainId())).findFirst().orElseThrow();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var match=executor.submit(()->{start.await();return propose();});
            var mutation=executor.submit(()->{start.await();new TransactionTemplate(transactions).executeWithoutResult(tx->{
                var group=groups.lockById(s.groupId()).orElseThrow();
                members.deleteById(victim.accountId());members.flush();group.rosterChanged();
            });return true;});
            start.countDown();var proposal=match.get(20,TimeUnit.SECONDS);assertTrue(mutation.get(20,TimeUnit.SECONDS));
            if(proposal.isPresent()) {
                var roster=tickets.findByProposalIdOrderByAccountId(proposal.get().getId());assertEquals(20,roster.size());
                assertTrue(roster.stream().anyMatch(t->t.getAccountId().equals(victim.accountId())));
                assertEquals(20,roster.stream().map(QueueTicket::getAccountId).distinct().count());
            } else for(var c:s.members())assertFalse(tickets.hasReservation(c.accountId()));
        }
    }
    @Test void privateApiBlocksRealAdmissionAndNeverExposesSimulationOrMmr() throws Exception {
        var s=snapshots.getFirst();enqueue(s);
        String subject=db.queryForObject("SELECT provider_subject FROM rbgs.accounts WHERE id=?",String.class,s.captainId());
        var login=oidcLogin().idToken(t->t.issuer("https://premade-fixture.example").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)));
        mvc.perform(get("/api/v1/me/premade-queue")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/local/premade/setup")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/premade-queue").with(login)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.ticket").isEmpty()).andExpect(jsonPath("$.room").isEmpty()).andExpect(jsonPath("$.mmr").doesNotExist());
        String body="{\"groupId\":\""+s.groupId()+"\",\"groupRevision\":"+s.revision()+"}";
        mvc.perform(post("/api/v1/me/premade-queue").with(login).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/premade-queue").with(login).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers").value(org.hamcrest.Matchers.hasItem("PREMADE_COMPETITIVE_DATA_UNAVAILABLE")));
        mvc.perform(delete("/api/v1/me/premade-queue/"+entries.findByGroupIdAndState(s.groupId(),"QUEUED").orElseThrow().getId()).with(login).with(csrf()))
                .andExpect(status().isNotFound());
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor login(UUID actor) {
        String subject=db.queryForObject("SELECT provider_subject FROM rbgs.accounts WHERE id=?",String.class,actor);
        return oidcLogin().idToken(t->t.issuer("https://premade-fixture.example").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)));
    }
    private org.springframework.test.web.servlet.ResultActions change(PremadeQueueCandidate group,UUID actor,String operation,UUID target) throws Exception {
        return mvc.perform(post("/api/v1/groups/"+group.groupId()+"/"+operation).with(login(actor)).with(csrf()).contentType("application/json")
                .content("{\"expectedRevision\":"+group.revision()+",\"accountId\":\""+target+"\"}"));
    }
    @Test void captainTransferImmediatelyCancelsWaitingGroupAndRequiresFreshAdmission() throws Exception {
        var s=snapshots.getFirst();var entry=enqueue(s);
        UUID next=s.members().stream().filter(c->!c.accountId().equals(s.captainId())).findFirst().orElseThrow().accountId();
        change(s,s.captainId(),"captain",next).andExpect(status().isOk());
        assertEquals("CANCELLED",entries.findById(entry.getId()).orElseThrow().getState());
        for(var t:tickets.findByPremadeEntryIdOrderByAccountId(entry.getId())) {
            assertEquals("CANCELLED",t.getState());assertEquals("LOBBY_CHANGED",t.getCancellationReason());
            assertFalse(tickets.hasReservation(t.getAccountId()));
        }
        var group=groups.findById(s.groupId()).orElseThrow();assertEquals(next,group.getCaptainId());
        var fresh=new PremadeQueueCandidate(s.groupId(),group.getRevision(),next,s.ratingSubjectType(),s.members());
        assertThrows(SecurityException.class,()->store.enqueueVerifiedSnapshot(s.captainId(),fresh));
        assertNotEquals(entry.getId(),store.enqueueVerifiedSnapshot(next,fresh).getId());
    }
    @Test void removalAndCaptainDepartureImmediatelyReleaseEveryWaitingMember() throws Exception {
        var first=snapshots.getFirst();var entry=enqueue(first);
        UUID member=first.members().stream().filter(c->!c.accountId().equals(first.captainId())).findFirst().orElseThrow().accountId();
        change(first,first.captainId(),"members/remove",member).andExpect(status().isOk());
        assertEquals(9,members.countByGroupId(first.groupId()));
        for(var c:first.members())assertFalse(tickets.hasReservation(c.accountId()));
        assertEquals("LOBBY_CHANGED",tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).getFirst().getCancellationReason());
        var second=snapshots.getLast();enqueue(second);
        change(second,second.captainId(),"members/remove",second.captainId()).andExpect(status().isOk());
        assertNotEquals(second.captainId(),groups.findById(second.groupId()).orElseThrow().getCaptainId());
        for(var c:second.members())assertFalse(tickets.hasReservation(c.accountId()));
    }
    @Test void failedOrUnauthorizedChangesAndVisualLabelsKeepSearchIntact() throws Exception {
        var s=snapshots.getFirst();var entry=enqueue(s);
        UUID member=s.members().stream().filter(c->!c.accountId().equals(s.captainId())).findFirst().orElseThrow().accountId();
        change(s,member,"captain",member).andExpect(status().isForbidden());
        change(s,s.captainId(),"members/remove",UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/groups/"+s.groupId()+"/role").with(login(member)).with(csrf()).contentType("application/json")
                .content("{\"expectedRoleVersion\":0,\"role\":\"FC\"}")).andExpect(status().isOk());
        assertEquals("QUEUED",entries.findById(entry.getId()).orElseThrow().getState());
        assertTrue(tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).stream().allMatch(t->t.getState().equals("QUEUED")));
    }
    @Test void proposedAndPreparingRoomsFreezeRosterUntilDeclinedOrEnded() throws Exception {
        var s=snapshots.getFirst();snapshots.forEach(this::enqueue);var proposal=propose().orElseThrow();
        UUID member=s.members().stream().filter(c->!c.accountId().equals(s.captainId())).findFirst().orElseThrow().accountId();
        change(s,s.captainId(),"captain",member).andExpect(status().isConflict());
        change(s,s.captainId(),"members/remove",member).andExpect(status().isConflict());
        assertEquals(10,members.countByGroupId(s.groupId()));
        assertEquals(s.captainId(),groups.findById(s.groupId()).orElseThrow().getCaptainId());
        for(var group:snapshots)for(var c:group.members())roomService.respond(c.accountId(),proposal.getId(),true);
        assertEquals("PREPARING",rooms.findById(proposal.getId()).orElseThrow().getStatus());
        change(s,s.captainId(),"members/remove",member).andExpect(status().isConflict());
        roomService.respond(member,proposal.getId(),false);
        change(s,s.captainId(),"members/remove",member).andExpect(status().isOk());
        assertEquals(9,members.countByGroupId(s.groupId()));
    }
    @Test void authenticatedRosterChangeRacingMatchEitherCancelsOrFreezesWholeGroup() throws Exception {
        var s=snapshots.getFirst();snapshots.forEach(this::enqueue);
        UUID member=s.members().stream().filter(c->!c.accountId().equals(s.captainId())).findFirst().orElseThrow().accountId();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var matching=executor.submit(()->{start.await();return propose();});
            var mutation=executor.submit(()->{start.await();return change(s,s.captainId(),"members/remove",member).andReturn().getResponse().getStatus();});
            start.countDown();var result=matching.get(20,TimeUnit.SECONDS);int response=mutation.get(20,TimeUnit.SECONDS);
            if(result.isPresent()) {
                assertEquals(409,response);assertEquals(10,members.countByGroupId(s.groupId()));
                assertEquals(20,tickets.findByProposalIdOrderByAccountId(result.get().getId()).size());
            } else {
                assertEquals(200,response);assertEquals(9,members.countByGroupId(s.groupId()));
                for(var c:s.members())assertFalse(tickets.hasReservation(c.accountId()));
            }
        }
    }
    @Test void failedLobbySwitchRollsBackCancellationButSuccessfulSwitchReleasesSource() throws Exception {
        var source=snapshots.getFirst();var destination=snapshots.getLast();var entry=enqueue(source);
        mvc.perform(post("/api/v1/groups/"+destination.groupId()+"/code").with(login(destination.captainId())).with(csrf())
                .contentType("application/json").content("{\"expectedRevision\":0,\"enabled\":true}")).andExpect(status().isOk());
        String code=groups.findById(destination.groupId()).orElseThrow().getJoinCode();
        mvc.perform(post("/api/v1/groups/join").with(login(source.captainId())).with(csrf()).contentType("application/json")
                .content("{\"code\":\""+code+"\"}")).andExpect(status().isConflict());
        assertEquals("QUEUED",entries.findById(entry.getId()).orElseThrow().getState());
        assertTrue(tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).stream().allMatch(t->t.getState().equals("QUEUED")));
        UUID removed=destination.members().stream().filter(c->!c.accountId().equals(destination.captainId())).findFirst().orElseThrow().accountId();
        change(destination,destination.captainId(),"members/remove",removed).andExpect(status().isOk());
        mvc.perform(post("/api/v1/groups/join").with(login(source.captainId())).with(csrf()).contentType("application/json")
                .content("{\"code\":\""+code+"\"}")).andExpect(status().isOk());
        assertEquals("CANCELLED",entries.findById(entry.getId()).orElseThrow().getState());
        for(var c:source.members())assertFalse(tickets.hasReservation(c.accountId()));
        assertEquals(destination.groupId(),members.findById(source.captainId()).orElseThrow().getGroupId());
    }
}
