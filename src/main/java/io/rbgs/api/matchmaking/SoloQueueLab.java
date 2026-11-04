package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.identity.*;
import io.rbgs.api.matches.MatchRoomRepository;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Synthetic actors exist only in the guarded, separate local lab database. */
@Service
@Profile("local & soloq-lab")
@RequiredArgsConstructor
public class SoloQueueLab {
    static final String ISSUER="https://soloq-lab.invalid";
    private final SoloQueueLabGuard guard;
    private final SoloQueueLabRepository lab;
    private final AccountService accounts;
    private final AccountRepository accountRows;
    private final CharacterIdentityRepository characters;
    private final SeasonSelectionRepository selections;
    private final SeasonRepository seasons;
    private final QueueTicketRepository tickets;
    private final QueueProposalRepository proposals;
    private final MatchRoomRepository roomRows;
    private final SoloQueueStore store;
    private final SoloQueueRooms rooms;

    public record Player(UUID id,String name,String role,boolean captainConsent) { }
    public record Ticket(UUID id,String state,Instant enteredAt,long version) { }
    public record Snapshot(boolean simulation,UUID generation,String scenario,Instant serverTime,UUID accountId,
            List<Player> players,long queueDepth,Ticket ticket,SoloQueueRooms.Room room,String revision) { }

    @Transactional
    public void reset(String scenario) {
        guard.check();
        if (!Set.of("NORMAL","NO_CAPTAINS","NO_HEALERS").contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        tickets.lockCoordinator();
        var state=lab.findById(1).orElse(null);
        if (state!=null) {
            UUID oldSeason=state.getSeasonId();
            tickets.deleteAll(tickets.findBySeasonId(oldSeason)); tickets.flush();
            var oldRooms=roomRows.findBySeasonId(oldSeason);
            roomRows.deleteAll(oldRooms); roomRows.flush();
            oldRooms.forEach(r->proposals.deleteById(r.getId())); proposals.flush();
            for (int i=1;i<=100;i++) {
                var account=accounts.findByIdentity(ISSUER,Integer.toString(i));
                selections.findByAccountIdAndSeasonId(account.id(),oldSeason).ifPresent(s->{
                    UUID character=s.getCharacterId(); selections.delete(s); selections.flush(); characters.deleteById(character);
                });
            }
        }
        var season=seasons.saveAndFlush(new SeasonEntity("SoloQ simulation",SeasonKind.BETA,RatingSubjectType.ACCOUNT));
        UUID oldSeason=state==null?null:state.getSeasonId();
        for (int i=1;i<=100;i++) {
            var account=accounts.upsert(ISSUER,Integer.toString(i),"Test player "+i);
            var character=characters.saveAndFlush(CharacterIdentity.declared(account.id(),"Simulation "+i,"Local simulation"));
            var selection=new SeasonSelection(account.id(),season.getId());
            int position=(i-1)%20;
            MatchRole role=position<2?MatchRole.FC:position<8&&!scenario.equals("NO_HEALERS")?MatchRole.HEALER:MatchRole.DPS;
            selection.choose(character.getId(),RatingSubjectType.ACCOUNT,role,!scenario.equals("NO_CAPTAINS"));
            selections.saveAndFlush(selection);
        }
        if (state==null) lab.saveAndFlush(new SoloQueueLabState(season.getId(),scenario));
        else { state.reset(season.getId(),scenario); lab.saveAndFlush(state); seasons.deleteById(oldSeason); }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(UUID account) {
        var state=lab.findById(1).orElse(null);
        if (state==null) return new Snapshot(true,null,"NORMAL",Instant.now(),null,List.of(),0,null,null,"empty");
        var players=lab.players(state.getSeasonId()).stream()
                .map(p->new Player((UUID)p[0],(String)p[1],(String)p[2],(Boolean)p[3])).toList();
        UUID selected=account!=null && players.stream().anyMatch(p->p.id().equals(account))?account:players.get(19).id();
        var ticket=tickets.active(selected).orElse(null);
        var recent=ticket!=null && ticket.getProposalId()!=null?ticket:
                tickets.recentRooms(selected,PageRequest.of(0,1)).stream().findFirst().orElse(null);
        var room=recent==null?null:rooms.own(selected,recent.getProposalId());
        long depth=tickets.countByState("QUEUED");
        String revision=state.getGeneration()+":"+selected+":"+players.hashCode()+":"+depth+":"+(ticket==null?"none":ticket.getId()+":"+ticket.getVersion())
                +":"+(room==null?"none":room.id()+":"+room.version());
        return new Snapshot(true,state.getGeneration(),state.getScenario(),Instant.now(),selected,players,depth,
                ticket==null?null:new Ticket(ticket.getId(),ticket.getState(),ticket.getEnteredAt(),ticket.getVersion()),room,revision);
    }

    @Transactional
    public void enter(UUID account) {
        tickets.lockCoordinator();
        store.enqueueVerifiedSnapshot(candidate(account,Instant.now()));
    }

    @Transactional
    public void addPlayers(UUID actor,int count) {
        if (count!=19 && count!=99) throw new IllegalArgumentException("Choose nineteen or ninety-nine test players");
        tickets.lockCoordinator();
        int added=0;
        for (int i=1;i<=100 && added<count;i++) {
            var account=accounts.findByIdentity(ISSUER,Integer.toString(i));
            if (account.id().equals(actor)) continue;
            if (!tickets.hasReservation(account.id())) store.enqueueVerifiedSnapshot(candidate(account.id(),Instant.now()));
            added++;
        }
    }
    private SoloQueueCandidate candidate(UUID account,Instant now) {
        var state=lab.findById(1).orElseThrow();
        var row=accountRows.findById(account).filter(a->a.getProviderIssuer().equals(ISSUER)).orElseThrow();
        var selection=selections.findByAccountIdAndSeasonId(account,state.getSeasonId()).orElseThrow();
        int index=Integer.parseInt(row.getProviderSubject());
        return new SoloQueueCandidate(account,selection.getCharacterId(),state.getSeasonId(),"EU","LOCAL_SIMULATION",
                "SIMULATED_GROUP",selection.getMatchRole(),selection.isCaptainConsent(),now,1400+index*3,
                List.of("Warrior","Priest","Mage","Rogue","Druid").get(index%5),roomRows.captainAssignments(state.getSeasonId(),account));
    }
    @Transactional
    public void choose(UUID account,MatchRole role,boolean captain) {
        tickets.lockCoordinator();
        if (tickets.hasReservation(account)) throw new IllegalStateException("Leave the queue before changing selection");
        var state=lab.findById(1).orElseThrow();
        var selection=selections.findByAccountIdAndSeasonId(account,state.getSeasonId()).orElseThrow();
        selection.choose(selection.getCharacterId(),selection.getRatingSubjectType(),role,captain);
    }
    public void acceptBots(UUID actor,UUID room) {
        rooms.own(actor,room);
        for (var ticket:tickets.findByProposalIdOrderByAccountId(room))
            if (!ticket.getAccountId().equals(actor)) rooms.respond(ticket.getAccountId(),room,true);
    }
    public void cycle() {
        rooms.expire(Instant.now());
        if (lab.existsById(1)) store.proposeCurrent((a,b)->a.equals("SIMULATED_GROUP")&&b.equals("SIMULATED_GROUP"));
    }
}
