package io.rbgs.api.matchmaking;

import io.rbgs.api.groups.*;
import io.rbgs.api.matches.MatchRoomEntity;
import io.rbgs.api.ratings.RatingResults;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class PremadeQueueService {
    private final PremadeGroupRepository groups;
    private final GroupMemberRepository members;
    private final QueueTicketRepository tickets;
    private final PremadeQueueEntryRepository entries;
    private final SoloQueueRooms rooms;
    private final PremadeQueueEligibility eligibility;
    private final PremadeQueueStore store;
    private final io.rbgs.api.identity.AccountRepository accounts;
    private final io.rbgs.api.matches.MatchRoomRepository roomRows;
    private final QueueProposalRepository proposals;
    private final SoloQueueGameGateway game;
    private final io.rbgs.api.matches.MatchRepository matches;
    private final io.rbgs.api.matches.MatchAssignmentRepository assignments;
    private final io.rbgs.api.characters.selection.CharacterIdentityRepository characters;
    private final RatingResults ratings;
    public record Admission(@NotNull UUID groupId,@NotNull @Min(0) Long groupRevision) { }
    public record SimulatedMatch(boolean captainAReady,boolean captainBReady,String assignment,String winner) {
        static SimulatedMatch forParticipant(MatchRoomEntity room,QueueTicket ticket) {
            return new SimulatedMatch(room.isCaptainAReady(),room.isCaptainBReady(),"LAB:"+ticket.getId(),room.getSimulatedWinner());
        }
    }
    public record RecentRoom(UUID id,String status,String winner,long version) { }
    public record Status(boolean simulation,UUID groupId,Long groupRevision,UUID captainId,boolean canControl,
            boolean available,List<String> blockers,SoloQueueService.Ticket ticket,SoloQueueRooms.Room room,
            UUID matchId,Instant serverTime,String revision,SimulatedMatch simulatedMatch,List<RecentRoom> recentRooms) { }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Status status(UUID account) { return snapshot(account,false); }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    Status simulationStatus(UUID account) { return snapshot(account,true); }

    private Status snapshot(UUID account,boolean simulation) {
        var group=members.findGroupIdByAccountId(account).flatMap(groups::findById).orElse(null);
        var blockers=new ArrayList<String>();
        if(group==null)blockers.add("GROUP_REQUIRED");
        else if(members.countByGroupId(group.getId())!=10)blockers.add("TEN_PLAYERS_REQUIRED");
        if(!simulation) {
            blockers.clear();blockers.addAll(eligibility.inspect(group==null?null:group.getId(),Instant.now()).blockers());
        }
        var latest=tickets.findByAccountIdAndSourceAndQueueTypeOrderByEnteredAtDescIdDesc(account,
                simulation?"SIMULATION":"FOREVER","PREMADE",PageRequest.of(0,1)).stream().findFirst().orElse(null);
        var entry=latest==null||latest.getPremadeEntryId()==null?null:entries.findById(latest.getPremadeEntryId()).orElse(null);
        var room=latest==null||latest.getProposalId()==null?null:rooms.own(account,latest.getProposalId());
        var roomRow=room==null?null:roomRows.findById(room.id()).orElseThrow();
        var simulatedMatch=simulation&&roomRow!=null&&roomRow.getPreparationDeadline()!=null
                &&!roomRow.getStatus().equals("CANCELLED")
                ? SimulatedMatch.forParticipant(roomRow,latest) : null;
        List<RecentRoom> recentRooms=simulation&&latest!=null
                ? roomRows.recentFinishedSimulations(account,latest.getSeasonId(),PageRequest.of(0,10)).stream()
                    .map(r->new RecentRoom(r.getId(),r.getStatus(),r.getSimulatedWinner(),r.getVersion())).toList()
                : List.of();
        boolean captain=group!=null&&group.getCaptainId().equals(account);
        boolean controls=captain&&(entry==null||latest.getState().equals("CANCELLED")||entry.getGroupId().equals(group.getId()));
        var ticket=entry==null?null:new SoloQueueService.Ticket(entry.getId(),latest.getState(),entry.getEnteredAt(),
                latest.getProposalId(),entry.getVersion()+latest.getVersion(),latest.getCancellationReason());
        String revision=blockers+":"+(group==null?"none":group.getId()+":"+group.getRevision()+":"+group.getCaptainId())
                +":"+(ticket==null?"none":ticket.id()+":"+ticket.version())+":"+(room==null?"none":room.id()+":"+room.version());
        if (!recentRooms.isEmpty()) revision+=":"+recentRooms.stream().map(r->r.id()+":"+r.version()).toList();
        return new Status(simulation,group==null?null:group.getId(),group==null?null:group.getRevision(),
                group==null?null:group.getCaptainId(),controls,blockers.isEmpty(),List.copyOf(blockers),ticket,room,
                roomRow==null?null:roomRow.getMatchId(),Instant.now(),revision,simulatedMatch,recentRooms);
    }

    @Transactional
    public boolean enter(UUID account,Admission request) {
        tickets.lockCoordinator();
        var group=groups.lockById(request.groupId()).orElseThrow();
        members.findById(account).filter(m->m.getGroupId().equals(group.getId())).orElseThrow();
        if(!group.getCaptainId().equals(account))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the captain can start the premade search");
        if(group.getRevision()!=request.groupRevision())throw new ResponseStatusException(HttpStatus.CONFLICT,"Lobby changed; reload Play");
        for(var member:members.findByGroupIdOrderByAccountId(group.getId()).stream().sorted(Comparator.comparing(GroupMember::getAccountId)).toList())
            accounts.findForSelection(member.getAccountId()).orElseThrow();
        var current=eligibility.inspect(group.getId(),Instant.now());
        if(!current.blockers().isEmpty())return false;
        store.enqueueReal(account,current);return true;
    }
    @Transactional
    public void cancel(UUID account,UUID entryId) {
        tickets.lockCoordinator();
        if(tickets.findByPremadeEntryIdOrderByAccountId(entryId).stream()
                .noneMatch(t->t.getAccountId().equals(account)&&t.getSource().equals("FOREVER")))throw new NoSuchElementException();
        store.cancel(account,entryId);
    }
    @Transactional
    public void respond(UUID account,UUID id,boolean accept) {
        tickets.lockCoordinator();
        var roster=tickets.findByProposalIdOrderByAccountId(id);
        if(roster.stream().noneMatch(t->t.getAccountId().equals(account)&&t.getSource().equals("FOREVER")&&t.getQueueType().equals("PREMADE")))
            throw new NoSuchElementException();
        var room=roomRows.findById(id).filter(r->r.getSource().equals("FOREVER")).orElseThrow();
        if(!room.getStatus().equals("PROPOSED")) {
            if(accept)return;
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Proposal is no longer awaiting acceptance");
        }
        if(!accept) { rooms.respond(account,id,false);return; }
        var entryIds=roster.stream().map(QueueTicket::getPremadeEntryId).filter(Objects::nonNull).distinct().toList();
        if(entryIds.size()!=2||roster.size()!=20)throw new IllegalStateException("Premade proposal must contain two complete groups");
        var groupIds=entryIds.stream().map(e->entries.findById(e).orElseThrow().getGroupId()).sorted().toList();
        for(var group:groupIds)if(groups.lockById(group).isEmpty()) { rooms.cancelRoom(id,"ELIGIBILITY_CHANGED");return; }
        for(var ticket:roster.stream().sorted(Comparator.comparing(QueueTicket::getAccountId)).toList())accounts.findForSelection(ticket.getAccountId()).orElseThrow();
        for(var entryId:entryIds) {
            var entry=entries.findById(entryId).orElseThrow();
            if(!store.matches(entry,eligibility.inspect(entry.getGroupId(),Instant.now()))) { rooms.cancelRoom(id,"ELIGIBILITY_CHANGED");return; }
        }
        rooms.respond(account,id,true);
        if(room.getStatus().equals("PREPARING")&&room.getMatchId()==null) {
            var expected=proposals.findById(id).orElseThrow().proposal();
            if(PreseasonQueue.isPreview(expected))return;
            UUID match=game.createMatch(expected,room.getPreparationDeadline().minusSeconds(600));
            var shared=matches.findById(match).filter(m->m.getMode()==io.rbgs.api.matches.MatchMode.MATCHMADE
                    &&m.getEvidencePolicy()==io.rbgs.api.matches.MatchEvidencePolicy.FULL_ROSTER&&m.getCancelledAt()==null
                    &&!Set.of("DARKSPEAR_DIAGNOSTIC","TBC_ANNIVERSARY_WSG_TEST").contains(m.getProfile()))
                    .orElseThrow(()->new IllegalStateException("Game adapter did not create an eligible shared Match"));
            var assigned=assignments.findByMatchId(match);
            if(assigned.size()!=20)throw new IllegalStateException("Shared Match requires twenty assignments");
            if(shared.getCaptureStartDeadline().isAfter(room.getPreparationDeadline()))throw new IllegalStateException("Shared Match exceeds the preparation deadline");
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            List<?> expectedRows=json.readValue(shared.getExpectedRoster(),List.class);
            if(expectedRows.size()!=20)throw new IllegalStateException("Shared Match requires the complete frozen evidence roster");
            for(var team:List.of(expected.teamA(),expected.teamB()))for(var player:team) {
                String key=team==expected.teamA()?"A":"B";
                if(assigned.stream().filter(a->a.getAccountId().equals(player.accountId())&&a.getCharacterId().equals(player.characterId())&&key.equals(a.getAssignedTeam())).count()!=1)
                    throw new IllegalStateException("Shared Match roster differs from the premade proposal");
                var character=characters.findById(player.characterId()).orElseThrow();
                if(expectedRows.stream().filter(row->row instanceof Map<?,?> m&&player.characterId().toString().equals(m.get("characterId"))
                        &&key.equals(m.get("assignedTeamKey"))&&m.get("character") instanceof Map<?,?> identity
                        &&character.getName().equals(identity.get("name"))&&character.getRealm().equals(identity.get("realm"))).count()!=1)
                    throw new IllegalStateException("Shared Match evidence identity differs from the proposal");
            }
            var first=entries.findById(entryIds.getFirst()).orElseThrow().candidate();
            ratings.freeze(match,id,first.seasonId(),ConfirmedRatingStore.Queue.PREMADE,first.ratingSubjectType(),expected);
            room.linkMatch(shared.getId());
        }
    }
}
