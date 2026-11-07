package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.groups.*;
import io.rbgs.api.identity.*;
import io.rbgs.api.matches.MatchRoomRepository;
import io.rbgs.api.matches.MatchRoomEntity;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Explicitly synthetic premades in the existing loopback-only, dedicated matchmaking lab. */
@Service @Profile("local & soloq-lab") @RequiredArgsConstructor
public class PremadeQueueLab {
    static final String ISSUER="https://premade-lab.invalid";
    private final SoloQueueLabGuard guard;
    private final PremadeQueueLabRepository lab;
    private final AccountService accounts;
    private final AccountRepository accountRows;
    private final CharacterIdentityRepository characters;
    private final SeasonSelectionRepository selections;
    private final SeasonRepository seasons;
    private final PremadeGroupRepository groups;
    private final GroupMemberRepository members;
    private final PremadeQueueEntryRepository entries;
    private final QueueTicketRepository tickets;
    private final QueueProposalRepository proposals;
    private final MatchRoomRepository roomRows;
    private final PremadeQueueStore store;
    private final PremadeQueueService queue;
    private final PremadeLobbyReservations reservations;
    private final SoloQueueRooms rooms;
    private final JdbcTemplate db;
    public record Player(UUID id,String name,UUID groupId,boolean captain) { }
    public record Setup(UUID generation,UUID accountId,List<Player> players,long queueDepth) { }
    public record PastRoom(SoloQueueRooms.Room room,PremadeQueueService.SimulatedMatch simulatedMatch,Instant serverTime) { }
    public enum MatchAction { READY, END_PLAY, SUBMIT_EVIDENCE, RESOLVE }

    @Transactional
    public void reset() {
        guard.check();tickets.lockCoordinator();
        var state=lab.findById(1).orElse(null);
        UUID oldSeason=state==null?null:state.getSeasonId();
        if(state!=null) {
            var oldTickets=tickets.findBySeasonId(oldSeason);
            var entryIds=oldTickets.stream().map(QueueTicket::getPremadeEntryId).filter(Objects::nonNull).distinct().toList();
            tickets.deleteAll(oldTickets);tickets.flush();
            entries.deleteAllById(entryIds);entries.flush();
            var oldRooms=roomRows.findBySeasonId(oldSeason);roomRows.deleteAll(oldRooms);roomRows.flush();
            oldRooms.forEach(r->proposals.deleteById(r.getId()));proposals.flush();
            var oldGroups=lab.players().stream().map(p->(UUID)p[2]).distinct().toList();
            groups.deleteAllById(oldGroups);groups.flush();
            for(int i=1;i<=100;i++) {
                var account=accounts.findByIdentity(ISSUER,Integer.toString(i));
                selections.findByAccountIdAndSeasonId(account.id(),oldSeason).ifPresent(s->{
                    UUID character=s.getCharacterId();selections.delete(s);selections.flush();characters.deleteById(character);
                });
            }
        }
        var season=seasons.saveAndFlush(new SeasonEntity("Premade simulation",SeasonKind.BETA,RatingSubjectType.ACCOUNT));
        PremadeGroup group=null;
        for(int i=1;i<=100;i++) {
            var account=accounts.upsert(ISSUER,Integer.toString(i),"Premade player "+i);
            db.update("INSERT INTO rbgs.play_characters(account_id,namespace,wow_account_id,realm_id,character_id,region,name,realm) "
                    +"VALUES (?,'profile-forever-eu',1,1,?,'EU',?,'Local simulation') ON CONFLICT(account_id) DO NOTHING",account.id(),i,"Simulation "+i);
            var character=characters.saveAndFlush(CharacterIdentity.declared(account.id(),"Simulation "+i,"Local simulation"));
            var selection=new SeasonSelection(account.id(),season.getId());
            // Intentionally all DPS, no volunteers: SoloQ composition/volunteer rules do not apply.
            selection.choose(character.getId(),RatingSubjectType.ACCOUNT,MatchRole.DPS,false);selections.saveAndFlush(selection);
            if((i-1)%10==0)group=groups.saveAndFlush(new PremadeGroup(account.id(),"EU"));
            members.saveAndFlush(new GroupMember(account.id(),group.getId()));
        }
        if(state==null)lab.saveAndFlush(new PremadeQueueLabState(season.getId()));
        else { state.reset(season.getId());lab.saveAndFlush(state);seasons.deleteById(oldSeason); }
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Setup setup(UUID actor) {
        var state=lab.findById(1).orElse(null);
        var players=state==null?List.<Player>of():lab.players().stream()
                .map(p->new Player((UUID)p[0],(String)p[1],(UUID)p[2],(Boolean)p[3])).toList();
        UUID selected=players.stream().anyMatch(p->p.id().equals(actor))?actor:players.isEmpty()?null:players.getFirst().id();
        long depth=state==null?0:tickets.findBySeasonId(state.getSeasonId()).stream().filter(t->t.getState().equals("QUEUED")).count()/10;
        return new Setup(state==null?null:state.getGeneration(),selected,players,depth);
    }
    public PremadeQueueService.Status status(UUID actor) { requireActor(actor);return queue.simulationStatus(actor); }
    private void requireActor(UUID actor) {
        if(actor==null||accountRows.findById(actor).filter(a->a.getProviderIssuer().equals(ISSUER)&&a.getStatus().equals("ACTIVE")).isEmpty())
            throw new NoSuchElementException();
        var state=lab.findById(1).orElseThrow();
        if(selections.findByAccountIdAndSeasonId(actor,state.getSeasonId()).isEmpty())throw new NoSuchElementException();
    }
    @Transactional
    public void enter(UUID actor,PremadeQueueService.Admission request) {
        tickets.lockCoordinator();requireActor(actor);
        var group=groups.lockById(request.groupId()).orElseThrow();
        if(!group.getCaptainId().equals(actor))throw new SecurityException("Only the captain can start the search");
        if(group.getRevision()!=request.groupRevision())throw new IllegalStateException("Lobby changed");
        var state=lab.findById(1).orElseThrow();
        var roster=members.findByGroupIdOrderByAccountId(group.getId());
        if(roster.stream().anyMatch(m->accountRows.findById(m.getAccountId()).filter(a->a.getProviderIssuer().equals(ISSUER)).isEmpty()))throw new IllegalStateException("Only simulated participants are allowed");
        if(roster.size()!=10)throw new IllegalStateException("Ten players required");
        var existing=entries.findByGroupIdAndState(group.getId(),"QUEUED");
        if(existing.isPresent())return;
        Instant now=Instant.now();var snapshots=new ArrayList<SoloQueueCandidate>();
        for(var member:roster) {
            UUID id=member.getAccountId();var selection=selections.findByAccountIdAndSeasonId(id,state.getSeasonId()).orElseThrow();
            snapshots.add(new SoloQueueCandidate(id,selection.getCharacterId(),state.getSeasonId(),"EU","PREMADE_LOCAL_SIMULATION",
                    "SIMULATED_PREMADE",MatchRole.DPS,false,now,1500,"Simulated class",0));
        }
        store.enqueueVerifiedSnapshot(actor,new PremadeQueueCandidate(group.getId(),group.getRevision(),actor,RatingSubjectType.ACCOUNT,snapshots));
    }
    @Transactional
    public void cancel(UUID actor,UUID entry) { requireActor(actor);store.cancel(actor,entry); }
    @Transactional
    public void transferCaptain(UUID actor,UUID target) {
        tickets.lockCoordinator();requireActor(actor);requireActor(target);
        UUID id=members.findGroupIdByAccountId(actor).orElseThrow();
        var group=groups.lockById(id).orElseThrow();
        if(!group.getCaptainId().equals(actor))throw new SecurityException("Only the captain can transfer this lobby");
        if(actor.equals(target)||members.findById(target).filter(m->m.getGroupId().equals(id)).isEmpty())
            throw new IllegalArgumentException("Choose another member of this group");
        reservations.beforeLockedLobbyChange(id);group.transfer(target);groups.flush();
    }
    @Transactional
    public void respond(UUID actor,UUID room,boolean accept) {
        requireActor(actor);requireRoom(actor,room);rooms.respond(actor,room,accept);
    }
    @Transactional
    public void acceptBots(UUID actor,UUID room) {
        requireActor(actor);requireRoom(actor,room);
        for(var t:tickets.findByProposalIdOrderByAccountId(room))if(!t.getAccountId().equals(actor))rooms.respond(t.getAccountId(),room,true);
    }

    @Transactional
    public void advance(UUID actor,UUID roomId,MatchAction action,String winner) {
        tickets.lockCoordinator();
        requireActor(actor);
        var room = requireRoom(actor,roomId);
        if (!actor.equals(room.getCaptainA()) && !actor.equals(room.getCaptainB()))
            throw new SecurityException("Only a match captain can run this simulation action");
        if (room.due(Instant.now())) {
            rooms.cancelRoom(roomId,"DEADLINE_EXPIRED");
            return;
        }
        switch (action) {
            case READY -> room.confirmSimulatedPreparation(actor);
            case END_PLAY -> {
                if (room.endSimulatedPlay())
                    tickets.findByProposalIdOrderByAccountId(roomId).forEach(QueueTicket::releaseRoom);
            }
            case SUBMIT_EVIDENCE -> room.submitSimulatedEvidence(winner);
            case RESOLVE -> room.resolveSimulation();
        }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PastRoom pastRoom(UUID actor,UUID roomId) {
        requireActor(actor);
        var room = requireRoom(actor,roomId);
        if (!Set.of("AWAITING_EVIDENCE","UNDER_REVIEW","RESOLVED").contains(room.getStatus()))
            throw new NoSuchElementException();
        var ticket = tickets.findByAccountIdAndProposalId(actor,roomId).orElseThrow();
        return new PastRoom(rooms.own(actor,roomId),PremadeQueueService.SimulatedMatch.forParticipant(room,ticket),Instant.now());
    }

    private MatchRoomEntity requireRoom(UUID actor,UUID room) {
        var state=lab.findById(1).orElseThrow();
        var row=roomRows.findById(room).filter(r->r.getSource().equals("SIMULATION")&&r.getSeasonId().equals(state.getSeasonId())).orElseThrow();
        tickets.findByAccountIdAndProposalId(actor,room)
                .filter(t->t.getSource().equals("SIMULATION")&&t.getQueueType().equals("PREMADE")&&t.getSeasonId().equals(row.getSeasonId()))
                .orElseThrow();
        return row;
    }
    @Transactional
    public void addOpponents(UUID actor,int count) {
        requireActor(actor);if(count!=1&&count!=9)throw new IllegalArgumentException("Choose one or nine opponent groups");
        UUID own=members.findGroupIdByAccountId(actor).orElseThrow();
        for(var player:setup(actor).players().stream().filter(p->p.captain()&&!p.groupId().equals(own)).limit(count).toList()) {
            var group=groups.findById(player.groupId()).orElseThrow();
            enter(player.id(),new PremadeQueueService.Admission(group.getId(),group.getRevision()));
        }
    }
    public void cycle() {
        if(!lab.existsById(1))return;
        rooms.expire(Instant.now());
        // Fixture-only policy; it does not establish the production widening policy.
        store.propose(Instant.now(),(r,a,b)->r.equals("PREMADE_LOCAL_SIMULATION")&&a.equals("SIMULATED_PREMADE")&&b.equals(a),
                wait->Math.min(600,100+50*(wait.toSeconds()/60)));
    }
}
