package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.identity.*;
import io.rbgs.api.matches.*;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The adapters below are test fixtures, never an activation path for Anniversary or live queues. */
@SpringBootTest @AutoConfigureMockMvc
class SoloQueuePlayIntegrationTests {
    @MockitoBean SoloQueueGameGateway game;
    @MockitoBean SoloQueueCompetitiveData competitive;
    @MockitoBean SelectionSettings settings;
    @Autowired SoloQueueService service;
    @Autowired SoloQueueEligibility eligibility;
    @Autowired SoloQueueStore store;
    @Autowired SoloQueueRooms rooms;
    @Autowired AccountService accounts;
    @Autowired SeasonRepository seasons;
    @Autowired SeasonSelectionRepository selections;
    @Autowired CharacterIdentityRepository characters;
    @Autowired QueueTicketRepository tickets;
    @Autowired MatchRepository matches;
    @Autowired MatchAssignmentRepository assignments;
    @Autowired JdbcTemplate db;
    @Autowired ConfirmedRatingStore ratings;
    @Autowired MockMvc mvc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    SeasonEntity season;
    final List<SeasonSelection> players=new ArrayList<>();
    final List<String> subjects=new ArrayList<>();
    static final String ISSUER="https://play-soloq-fixture.example";
    @BeforeEach void setup() {
        season=new SeasonEntity("Play fixture",SeasonKind.BETA,RatingSubjectType.ACCOUNT);
        season.start(Instant.now().minusSeconds(60));season=seasons.saveAndFlush(season);
        when(settings.seasonId()).thenReturn(season.getId());
        when(game.handoffAvailable()).thenReturn(true);
        when(game.canOppose(anyString(),anyString(),anyString())).thenReturn(true);
        when(game.inspect(any(),any(),any())).thenAnswer(inv->{
            UUID account=inv.getArgument(0);SeasonEntity s=inv.getArgument(1);SeasonSelection selected=inv.getArgument(2);
            return Optional.of(new SoloQueueGameGateway.VerifiedPlayer(account,s.getId(),s.getVersion(),selected.getCharacterId(),selected.getVersion(),
                selected.getCompanionDeviceId(),"WOW_FOREVER","EU","fixture","fixture","WARRIOR",Instant.now().minusSeconds(1),Instant.now().plusSeconds(30)));
        });
        when(competitive.confirmed(any(),any(),any())).thenAnswer(inv->Optional.of(new SoloQueueCompetitiveData.Snapshot(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),1500,0,0,0)));
        when(game.createMatch(any(),any())).thenAnswer(inv->{
            SoloQueueMatcher.Proposal proposal=inv.getArgument(0);Instant now=inv.getArgument(1);
            var match=matches.saveAndFlush(new MatchEntity(proposal.captainA(),now,now.plusSeconds(600),"[]",MatchMode.MATCHMADE,MatchEvidencePolicy.FULL_ROSTER,"FOREVER_FIXTURE",now.plusSeconds(3600)));
            for(var p:proposal.teamA())assignments.save(new MatchAssignmentEntity(match,p.accountId(),p.characterId(),"A"));
            for(var p:proposal.teamB())assignments.save(new MatchAssignmentEntity(match,p.accountId(),p.characterId(),"B"));
            assignments.flush();return match.getId();
        });
        for(int i=0;i<20;i++) {
            String subject=UUID.randomUUID().toString();subjects.add(subject);
            var account=accounts.upsert(ISSUER,subject,"PrivateContact#"+i);
            var character=characters.saveAndFlush(CharacterIdentity.declared(account.id(),"Player"+i,"Realm"));
            UUID device=UUID.randomUUID();
            db.update("INSERT INTO rbgs.devices(id,account_id,token_hash,created_at,expires_at,granted_scope,last_seen_at) VALUES(?,?,?,now(),now()+interval '1 day','companion:status companion:assignments evidence:write',now())",device,account.id(),UUID.randomUUID().toString());
            var selected=new SeasonSelection(account.id(),season.getId());selected.choose(character.getId(),RatingSubjectType.ACCOUNT,i<2?MatchRole.FC:i<8?MatchRole.HEALER:MatchRole.DPS,false);selected.useDevice(device);
            selected=selections.saveAndFlush(selected);
            players.add(selected);
            ratings.initializeSelection(selected.getId());
        }
    }
    @AfterEach void cleanup() {
        var roomIds=db.queryForList("SELECT id FROM rbgs.match_rooms WHERE season_id=?",UUID.class,season.getId());
        db.update("DELETE FROM rbgs.queue_tickets WHERE season_id=?",season.getId());
        db.update("DELETE FROM rbgs.match_competitive_context WHERE season_id=?",season.getId());
        db.update("DELETE FROM rbgs.match_rooms WHERE season_id=?",season.getId());
        for(var id:roomIds)db.update("DELETE FROM rbgs.queue_proposals WHERE id=?",id);
        for(var p:players)db.update("DELETE FROM rbgs.match_assignments WHERE account_id=?",p.getAccountId());
        for(var p:players) {
            db.update("DELETE FROM rbgs.matches WHERE created_by=?",p.getAccountId());
            db.update("DELETE FROM rbgs.season_selections WHERE account_id=?",p.getAccountId());
            db.update("DELETE FROM rbgs.devices WHERE account_id=?",p.getAccountId());
            db.update("DELETE FROM rbgs.character_identities WHERE account_id=?",p.getAccountId());
            db.update("DELETE FROM rbgs.accounts WHERE id=?",p.getAccountId());
        }
        seasons.deleteById(season.getId());
    }
    SoloQueueService.Admission request(SeasonSelection p){return new SoloQueueService.Admission(season.getId(),season.getVersion(),p.getVersion());}
    UUID propose(){for(var p:players)assertTrue(service.enter(p.getAccountId(),request(p)));return store.proposeReal().orElseThrow().getId();}
    @Test void twentyIndividualAcceptancesCreateOneSharedMatchAndReloadRecoversIt() {
        UUID room=propose();
        for(int i=0;i<19;i++)service.respond(players.get(i).getAccountId(),room,true);
        assertEquals("PROPOSED",service.status(players.getFirst().getAccountId()).room().status());
        assertNull(service.status(players.getFirst().getAccountId()).matchId());verify(game,never()).createMatch(any(),any());
        service.respond(players.getLast().getAccountId(),room,true);
        var status=service.status(players.getFirst().getAccountId());assertEquals("PREPARING",status.room().status());assertNotNull(status.matchId());
        assertEquals(20,assignments.findByMatchId(status.matchId()).size());
        service.respond(players.getLast().getAccountId(),room,true);verify(game,times(1)).createMatch(any(),any());
    }
    @Test void declineAndExpiryReleaseAllReservationsWithoutCreatingMatch() {
        UUID room=propose();service.respond(players.getFirst().getAccountId(),room,false);
        assertEquals("PARTICIPANT_DECLINED",rooms.own(players.getLast().getAccountId(),room).cancellationReason());
        for(var p:players)assertFalse(tickets.hasReservation(p.getAccountId()));
        room=propose();rooms.expireReal(rooms.own(players.getFirst().getAccountId(),room).acceptanceDeadline());
        assertEquals("DEADLINE_EXPIRED",rooms.own(players.getLast().getAccountId(),room).cancellationReason());
        for(var p:players)assertFalse(tickets.hasReservation(p.getAccountId()));verify(game,never()).createMatch(any(),any());
    }
    @Test void invalidatedDeviceCancelsProposalBeforeAnyAcceptance() {
        UUID room=propose();db.update("UPDATE rbgs.devices SET revoked_at=now() WHERE id=?",players.getLast().getCompanionDeviceId());
        service.respond(players.getFirst().getAccountId(),room,true);
        assertEquals("ELIGIBILITY_CHANGED",rooms.own(players.getFirst().getAccountId(),room).cancellationReason());
        for(var p:players)assertFalse(tickets.hasReservation(p.getAccountId()));
    }
    @Test void missingProofAndCompetitiveDataStayClosed() {
        doReturn(Optional.empty()).when(game).inspect(any(),any(),any());doReturn(Optional.empty()).when(competitive).confirmed(any(),any(),any());
        var p=players.getFirst();assertFalse(service.enter(p.getAccountId(),request(p)));
        assertTrue(service.status(p.getAccountId()).blockers().containsAll(List.of("FOREVER_COMPATIBILITY_UNVERIFIED","GAME_PRESENCE_UNVERIFIED","COMPETITIVE_DATA_UNAVAILABLE")));
        assertFalse(tickets.hasReservation(p.getAccountId()));
    }
    @Test void ownResponseCannotConfirmAnotherPlayerAndCsrfIsMandatory() throws Exception {
        UUID room=propose();
        var token=OidcIdToken.withTokenValue("fixture").issuer(ISSUER).subject(subjects.getFirst()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        var login=oidcLogin().idToken(b->b.issuer(ISSUER).subject(subjects.getFirst()));
        String path="/api/v1/me/solo-queue/rooms/"+room+"/response";
        mvc.perform(post(path).with(login).contentType("application/json").content("{\"accept\":true}")).andExpect(status().isForbidden());
        mvc.perform(post(path).with(login).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(login).with(csrf()).contentType("application/json").content("{\"accept\":true,\"accountId\":\""+players.getLast().getAccountId()+"\"}")).andExpect(status().isOk());
        var accepted=tickets.findByProposalIdOrderByAccountId(room).stream().filter(t->t.getAcceptedAt()!=null).toList();
        assertEquals(1,accepted.size());assertEquals(players.getFirst().getAccountId(),accepted.getFirst().getAccountId());
        assertThrows(NoSuchElementException.class,()->service.respond(UUID.randomUUID(),room,true));
    }
    @Test void failedHandoffRollsBackLastAcceptance() {
        UUID room=propose();for(int i=0;i<19;i++)service.respond(players.get(i).getAccountId(),room,true);
        doThrow(new IllegalStateException("Adapter unavailable")).when(game).createMatch(any(),any());
        assertThrows(IllegalStateException.class,()->service.respond(players.getLast().getAccountId(),room,true));
        assertEquals("PROPOSED",rooms.own(players.getLast().getAccountId(),room).status());
        assertEquals(19,tickets.findByProposalIdOrderByAccountId(room).stream().filter(t->t.getAcceptedAt()!=null).count());
    }
    @Test void staleOrAnniversaryProofCannotAdmitAndVersionsMustMatch() {
        var p=players.getFirst();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.enter(p.getAccountId(),new SoloQueueService.Admission(season.getId(),season.getVersion(),99L)));
        for(String product:List.of("WOW_FOREVER","TBC_ANNIVERSARY")) {
            doReturn(Optional.of(new SoloQueueGameGateway.VerifiedPlayer(p.getAccountId(),season.getId(),season.getVersion(),p.getCharacterId(),p.getVersion(),p.getCompanionDeviceId(),product,"EU","fixture","fixture","WARRIOR",Instant.now().minusSeconds(60),Instant.now().plusSeconds(60))))
                .when(game).inspect(any(),any(),any());
            assertFalse(service.enter(p.getAccountId(),request(p)));
        }
        assertFalse(tickets.hasReservation(p.getAccountId()));
    }
    @Test void simultaneousAcceptancesStillCreateOnlyOneMatch() throws Exception {
        UUID room=propose();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var results=players.stream().map(p->executor.submit(()->service.respond(p.getAccountId(),room,true))).toList();
            for(var result:results)result.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals("PREPARING",rooms.own(players.getFirst().getAccountId(),room).status());verify(game,times(1)).createMatch(any(),any());
        assertTrue(rooms.own(players.getFirst().getAccountId(),room).teamA().stream().noneMatch(m->m.name().contains("#")));
    }
    @Test void queueNotificationsAreDeliveredOnlyAfterCommit() throws Exception {
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try(var statement=connection.createStatement()){statement.execute("LISTEN rbgs_solo_queue");}
            var pg=connection.unwrap(org.postgresql.PGConnection.class);var p=players.getFirst();
            new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{assertTrue(service.enter(p.getAccountId(),request(p)));status.setRollbackOnly();return null;});
            var rolledBack=pg.getNotifications(100);assertTrue(rolledBack==null||rolledBack.length==0);assertFalse(tickets.hasReservation(p.getAccountId()));
            assertTrue(service.enter(p.getAccountId(),request(p)));
            var notifications=pg.getNotifications(1000);assertNotNull(notifications);
            assertTrue(Arrays.stream(notifications).anyMatch(n->n.getParameter().equals(p.getAccountId().toString())));
            try(var statement=connection.createStatement()){statement.execute("UNLISTEN rbgs_solo_queue");}
        }
    }
}
