package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.groups.*;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.matches.*;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only the game gateway is synthetic. Ratings, admission, reservations and shared Matches use real persistence. */
@SpringBootTest @AutoConfigureMockMvc
class PremadeQueueRealIntegrationTests {
    @MockitoBean SoloQueueGameGateway game;
    @MockitoBean SelectionSettings settings;
    @Autowired PremadeQueueService service;
    @Autowired PremadeQueueStore store;
    @Autowired PremadeQueueEligibility eligibility;
    @Autowired ConfirmedRatingStore ratings;
    @Autowired AccountService accounts;
    @Autowired SeasonRepository seasons;
    @Autowired SeasonSelectionRepository selections;
    @Autowired CharacterIdentityRepository characters;
    @Autowired PremadeGroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired QueueTicketRepository tickets;
    @Autowired MatchRepository matches;
    @Autowired MatchAssignmentRepository assignments;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    private SeasonEntity season;
    private final List<SeasonSelection> players=new ArrayList<>();
    private final List<UUID> createdAccounts=new ArrayList<>();
    private final List<PremadeGroup> lobbies=new ArrayList<>();
    private final AtomicBoolean brokenHandoff=new AtomicBoolean();
    private static final String ISSUER="https://premade-real-fixture.example";

    @BeforeEach void setup() {
        season=new SeasonEntity("Real premade fixture",SeasonKind.BETA,RatingSubjectType.ACCOUNT);season.start(Instant.now().minusSeconds(60));
        season=seasons.saveAndFlush(season);when(settings.seasonId()).thenReturn(season.getId());
        when(game.handoffAvailable()).thenReturn(true);when(game.canOppose(anyString(),anyString(),anyString())).thenReturn(true);
        when(game.inspect(any(),any(),any())).thenAnswer(inv->{
            UUID account=inv.getArgument(0);SeasonEntity s=inv.getArgument(1);SeasonSelection selected=inv.getArgument(2);
            return Optional.of(new SoloQueueGameGateway.VerifiedPlayer(account,s.getId(),s.getVersion(),selected.getCharacterId(),selected.getVersion(),
                    selected.getCompanionDeviceId(),"WOW_FOREVER","EU","forever-fixture","verified-fixture-group","WARRIOR",Instant.now().minusSeconds(1),Instant.now().plusSeconds(30)));
        });
        for(int i=0;i<20;i++) {
            var account=accounts.upsert(ISSUER,UUID.randomUUID().toString(),"PrivateContact#"+i);
            createdAccounts.add(account.id());
            var character=characters.saveAndFlush(CharacterIdentity.declared(account.id(),"Player"+i,"Realm"));
            UUID device=UUID.randomUUID();
            db.update("INSERT INTO rbgs.devices(id,account_id,token_hash,created_at,expires_at,granted_scope,last_seen_at) VALUES(?,?,?,now(),now()+interval '1 day','companion:status companion:assignments evidence:write',now())",device,account.id(),UUID.randomUUID().toString());
            db.update("INSERT INTO rbgs.play_characters(account_id,namespace,wow_account_id,realm_id,character_id,region,name,realm) VALUES (?,'profile-forever-eu',1,1,?,'EU',?,'Realm')",account.id(),i+1,"Player"+i);
            var selected=new SeasonSelection(account.id(),season.getId());selected.choose(character.getId(),RatingSubjectType.ACCOUNT,MatchRole.DPS,false);selected.useDevice(device);
            selected=selections.saveAndFlush(selected);players.add(selected);ratings.initializeSelection(selected.getId());
            db.update("UPDATE rbgs.competitive_ratings SET mmr=CASE WHEN queue_type='PREMADE' THEN 1600 ELSE 700 END WHERE account_id=?",account.id());
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            for(int team=0;team<2;team++) {
                var group=groups.saveAndFlush(new PremadeGroup(players.get(team*10).getAccountId(),"EU"));lobbies.add(group);
                for(var selected:players.subList(team*10,team*10+10))members.saveAndFlush(new GroupMember(selected.getAccountId(),group.getId()));
            }
        });
        when(game.createMatch(any(),any())).thenAnswer(inv->{
            SoloQueueMatcher.Proposal p=inv.getArgument(0);Instant now=inv.getArgument(1);
            var frozen=new ArrayList<Map<String,Object>>();
            for(var team:List.of(p.teamA(),p.teamB()))for(var c:team) {
                var character=characters.findById(c.characterId()).orElseThrow();
                frozen.add(Map.of("characterId",c.characterId(),"character",Map.of("name",character.getName(),"realm",character.getRealm()),"assignedTeamKey",team==p.teamA()?"A":"B"));
            }
            String roster=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(frozen);
            var match=matches.saveAndFlush(new MatchEntity(p.captainA(),now,now.plusSeconds(600),roster,MatchMode.MATCHMADE,
                    MatchEvidencePolicy.FULL_ROSTER,"FOREVER_WSG_FIXTURE",now.plusSeconds(3600)));
            for(var c:p.teamA())assignments.save(new MatchAssignmentEntity(match,c.accountId(),c.characterId(),"A"));
            for(var c:p.teamB())if(!brokenHandoff.get()||!c.equals(p.teamB().getLast()))assignments.save(new MatchAssignmentEntity(match,c.accountId(),c.characterId(),"B"));
            assignments.flush();return match.getId();
        });
    }
    @AfterEach void cleanup() {
        var roomIds=db.queryForList("SELECT id FROM rbgs.match_rooms WHERE season_id=?",UUID.class,season.getId());
        var matchIds=db.queryForList("SELECT match_id FROM rbgs.match_rooms WHERE season_id=? AND match_id IS NOT NULL",UUID.class,season.getId());
        db.update("DELETE FROM rbgs.match_competitive_context WHERE season_id=?",season.getId());
        db.update("DELETE FROM rbgs.queue_tickets WHERE season_id=?",season.getId());
        lobbies.forEach(g->db.update("DELETE FROM rbgs.premade_queue_entries WHERE group_id=?",g.getId()));
        db.update("DELETE FROM rbgs.match_rooms WHERE season_id=?",season.getId());
        roomIds.forEach(id->db.update("DELETE FROM rbgs.queue_proposals WHERE id=?",id));
        matchIds.forEach(id->{db.update("DELETE FROM rbgs.match_assignments WHERE match_id=?",id);db.update("DELETE FROM rbgs.matches WHERE id=?",id);});
        lobbies.forEach(g->groups.deleteById(g.getId()));
        for(var account:createdAccounts) {
            db.update("DELETE FROM rbgs.season_selections WHERE account_id=?",account);
            db.update("DELETE FROM rbgs.devices WHERE account_id=?",account);
            db.update("DELETE FROM rbgs.character_identities WHERE account_id=?",account);
            db.update("DELETE FROM rbgs.accounts WHERE id=?",account);
        }
        seasons.deleteById(season.getId());
    }
    private boolean enter(PremadeGroup group) { return service.enter(group.getCaptainId(),new PremadeQueueService.Admission(group.getId(),group.getRevision())); }
    private UUID propose() {
        lobbies.forEach(g->assertTrue(enter(g)));
        return store.proposeReal().orElseThrow().getId();
    }
    private void acceptNineteen(UUID room) { for(var p:players.subList(0,19))service.respond(p.getAccountId(),room,true); }

    @Test void confirmedPremadeRatingsAndTwentyAcceptancesCreateOneFrozenSharedMatch() {
        var first=lobbies.getFirst();assertTrue(enter(first));var entry=service.status(first.getCaptainId()).ticket().id();
        assertTrue(enter(first));assertEquals(entry,service.status(first.getCaptainId()).ticket().id());assertTrue(enter(lobbies.getLast()));
        var proposal=store.proposeReal().orElseThrow();UUID room=proposal.getId();
        assertTrue(proposal.proposal().teamA().stream().allMatch(c->c.mmr()==1600));
        acceptNineteen(room);verify(game,never()).createMatch(any(),any());
        service.respond(players.getLast().getAccountId(),room,true);
        var status=service.status(first.getCaptainId());assertEquals("PREPARING",status.room().status());assertNotNull(status.matchId());
        assertEquals(20,assignments.findByMatchId(status.matchId()).size());
        assertEquals("PREMADE",db.queryForObject("SELECT queue_type FROM rbgs.match_competitive_context WHERE match_id=?",String.class,status.matchId()));
        assertTrue(db.queryForObject("SELECT snapshot FROM rbgs.match_competitive_context WHERE match_id=?",String.class,status.matchId()).contains("1600"));
        service.respond(players.getLast().getAccountId(),room,true);verify(game,times(1)).createMatch(any(),any());
        assertEquals(700,ratings.confirmed(season.getId(),ConfirmedRatingStore.Queue.SOLO,RatingSubjectType.ACCOUNT,first.getCaptainId()).orElseThrow().mmr());
    }
    @Test void missingGameProofOrRevokedDeviceNeverAdmitsTheGroup() {
        var player=players.getFirst();doReturn(Optional.empty()).when(game).inspect(eq(player.getAccountId()),any(),any());
        assertFalse(enter(lobbies.getFirst()));assertFalse(tickets.hasReservation(player.getAccountId()));
        assertTrue(eligibility.inspect(lobbies.getFirst().getId(),Instant.now()).blockers().contains("GAME_PRESENCE_UNVERIFIED"));
        db.update("UPDATE rbgs.devices SET revoked_at=now() WHERE id=?",players.get(10).getCompanionDeviceId());
        assertFalse(enter(lobbies.getLast()));assertFalse(tickets.hasReservation(players.get(10).getAccountId()));
    }
    @Test void deviceInvalidationIsRecheckedBeforeReservationAndCancelsAllTen() {
        lobbies.forEach(g->assertTrue(enter(g)));
        db.update("UPDATE rbgs.devices SET last_seen_at=now()-interval '1 minute' WHERE id=?",players.getFirst().getCompanionDeviceId());
        assertTrue(store.proposeReal().isEmpty());for(var p:players.subList(0,10))assertFalse(tickets.hasReservation(p.getAccountId()));
        assertEquals("QUEUED",tickets.active(players.get(10).getAccountId()).orElseThrow().getState());
    }
    @Test void changedConfirmedMmrBeforeLastAcceptanceCancelsWithoutHandoff() {
        UUID room=propose();acceptNineteen(room);
        db.update("UPDATE rbgs.competitive_ratings SET mmr=1700,version=version+1 WHERE queue_type='PREMADE' AND subject_id=?",players.getLast().getAccountId());
        service.respond(players.getLast().getAccountId(),room,true);assertEquals("CANCELLED",service.status(players.getFirst().getAccountId()).room().status());
        for(var p:players)assertFalse(tickets.hasReservation(p.getAccountId()));verify(game,never()).createMatch(any(),any());
    }
    @Test void incompleteGameHandoffRollsBackTwentiethAcceptanceAndCanRetry() {
        UUID room=propose();acceptNineteen(room);brokenHandoff.set(true);
        assertThrows(IllegalStateException.class,()->service.respond(players.getLast().getAccountId(),room,true));
        assertEquals("PROPOSED",service.status(players.getLast().getAccountId()).room().status());
        assertNull(tickets.active(players.getLast().getAccountId()).orElseThrow().getAcceptedAt());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM rbgs.match_competitive_context WHERE season_id=?",Integer.class,season.getId()));
        brokenHandoff.set(false);service.respond(players.getLast().getAccountId(),room,true);
        assertNotNull(service.status(players.getLast().getAccountId()).matchId());
    }
    @Test void concurrentFinalAcceptancesCommitOnlyOneSharedMatch() throws Exception {
        UUID room=propose();acceptNineteen(room);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();service.respond(players.getLast().getAccountId(),room,true);return true;});
            var b=executor.submit(()->{start.await();service.respond(players.getLast().getAccountId(),room,true);return true;});
            start.countDown();assertTrue(a.get(20,TimeUnit.SECONDS));assertTrue(b.get(20,TimeUnit.SECONDS));
        }
        verify(game,times(1)).createMatch(any(),any());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM rbgs.match_competitive_context WHERE season_id=?",Integer.class,season.getId()));
    }
    @Test void initialRatingIsDurableQueueSpecificAndNeverResetBySelectionRefresh() {
        var p=players.getFirst();ratings.initializeSelection(p.getId());assertEquals(1600,ratings.confirmed(season.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.ACCOUNT,p.getAccountId()).orElseThrow().mmr());
        assertTrue(ratings.confirmed(season.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.CHARACTER,p.getCharacterId()).isEmpty());
        db.update("DELETE FROM rbgs.competitive_ratings WHERE queue_type='PREMADE' AND subject_id=?",p.getAccountId());ratings.initializeSelection(p.getId());
        var initial=ratings.confirmed(season.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.ACCOUNT,p.getAccountId()).orElseThrow();
        assertEquals(1500,initial.mmr());assertEquals(0,initial.visibleRating());assertEquals(0,initial.gamesPlayed());
        assertEquals(700,ratings.confirmed(season.getId(),ConfirmedRatingStore.Queue.SOLO,RatingSubjectType.ACCOUNT,p.getAccountId()).orElseThrow().mmr());
    }
    @Test void authenticatedApiExcludesMmrAndRequiresCaptainAndCsrf() throws Exception {
        var member=players.get(1);String subject=db.queryForObject("SELECT provider_subject FROM rbgs.accounts WHERE id=?",String.class,member.getAccountId());
        var login=oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)));
        var g=lobbies.getFirst();String body="{\"groupId\":\""+g.getId()+"\",\"groupRevision\":"+g.getRevision()+"}";
        mvc.perform(get("/api/v1/me/premade-queue").with(login)).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.mmr").doesNotExist());
        mvc.perform(post("/api/v1/me/premade-queue").with(login).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        assertTrue(enter(g));UUID entry=service.status(g.getCaptainId()).ticket().id();
        mvc.perform(delete("/api/v1/me/premade-queue/"+entry).with(login).with(csrf())).andExpect(status().isForbidden());
        service.cancel(g.getCaptainId(),entry);for(var p:players.subList(0,10))assertFalse(tickets.hasReservation(p.getAccountId()));
    }
    @Test void newSeasonStartsFreshWithoutImportingEarlierPremadeMmr() {
        var p=players.getFirst();var next=seasons.saveAndFlush(new SeasonEntity("Next rating fixture",SeasonKind.BETA,RatingSubjectType.ACCOUNT));
        var selected=new SeasonSelection(p.getAccountId(),next.getId());selected.choose(p.getCharacterId(),RatingSubjectType.ACCOUNT,MatchRole.DPS,false);
        selected=selections.saveAndFlush(selected);
        try {
            assertTrue(ratings.confirmed(next.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.ACCOUNT,p.getAccountId()).isEmpty());
            ratings.initializeSelection(selected.getId());
            assertEquals(1500,ratings.confirmed(next.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.ACCOUNT,p.getAccountId()).orElseThrow().mmr());
            assertEquals(1600,ratings.confirmed(season.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.ACCOUNT,p.getAccountId()).orElseThrow().mmr());
        } finally { selections.deleteById(selected.getId());seasons.deleteById(next.getId()); }
    }
    @Test void characterSeasonOwnsIndependentCharacterSubjects() {
        var p=players.getFirst();UUID character=UUID.randomUUID();
        db.update("INSERT INTO rbgs.character_identities(id,account_id,product,region,source,name,realm,provider_namespace,provider_realm_id,provider_character_id) "
                +"VALUES (?,?,'WOW_FOREVER','EU','BLIZZARD_VERIFIED','VerifiedFixture','Realm','profile-forever-eu','1','123')",character,p.getAccountId());
        var next=seasons.saveAndFlush(new SeasonEntity("Character rating fixture",SeasonKind.BETA,RatingSubjectType.CHARACTER));
        var selected=new SeasonSelection(p.getAccountId(),next.getId());selected.choose(character,RatingSubjectType.CHARACTER,MatchRole.DPS,false);selected=selections.saveAndFlush(selected);
        try {
            ratings.initializeSelection(selected.getId());
            assertEquals(1500,ratings.confirmed(next.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.CHARACTER,character).orElseThrow().mmr());
            assertTrue(ratings.confirmed(next.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.ACCOUNT,p.getAccountId()).isEmpty());
            assertTrue(ratings.confirmed(season.getId(),ConfirmedRatingStore.Queue.PREMADE,RatingSubjectType.CHARACTER,character).isEmpty());
        } finally { selections.deleteById(selected.getId());seasons.deleteById(next.getId()); }
    }
    @Test void freshDeviceHeartbeatCannotReplaceFreshBoundGameProof() {
        var p=players.getFirst();
        var stale=new SoloQueueGameGateway.VerifiedPlayer(p.getAccountId(),season.getId(),season.getVersion(),p.getCharacterId(),p.getVersion(),p.getCompanionDeviceId(),
                "WOW_FOREVER","EU","forever-fixture","verified-fixture-group","WARRIOR",Instant.now().minusSeconds(60),Instant.now().plusSeconds(30));
        doReturn(Optional.of(stale)).when(game).inspect(eq(p.getAccountId()),any(),any());
        assertFalse(enter(lobbies.getFirst()));assertFalse(tickets.hasReservation(p.getAccountId()));
    }
    @Test void illegalWithinTeamGroupingNeverEntersTheQueue() {
        var p=players.getFirst();
        var incompatible=new SoloQueueGameGateway.VerifiedPlayer(p.getAccountId(),season.getId(),season.getVersion(),p.getCharacterId(),p.getVersion(),p.getCompanionDeviceId(),
                "WOW_FOREVER","EU","forever-fixture","different-grouping-key","WARRIOR",Instant.now().minusSeconds(1),Instant.now().plusSeconds(30));
        doReturn(Optional.of(incompatible)).when(game).inspect(eq(p.getAccountId()),any(),any());
        assertFalse(enter(lobbies.getFirst()));assertTrue(eligibility.inspect(lobbies.getFirst().getId(),Instant.now()).blockers().contains("GROUPING_INCOMPATIBLE"));
    }
}
