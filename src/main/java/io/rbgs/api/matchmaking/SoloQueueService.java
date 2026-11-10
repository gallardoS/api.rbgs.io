package io.rbgs.api.matchmaking;

import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.matches.*;
import io.rbgs.api.ratings.RatingResults;
import io.rbgs.api.seasons.SeasonRepository;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class SoloQueueService {
    private final SoloQueueEligibility eligibility;
    private final QueueTicketRepository tickets;
    private final QueueProposalRepository proposals;
    private final MatchRoomRepository roomRows;
    private final MatchRepository matches;
    private final MatchAssignmentRepository assignments;
    private final AccountRepository accounts;
    private final QueueEligibilityRoster eligibilityRoster;
    private final SoloQueueRooms rooms;
    private final SoloQueueStore store;
    private final SoloQueueGameGateway game;
    private final SeasonRepository seasons;
    private final RatingResults ratings;
    public record Admission(@NotNull UUID seasonId,@NotNull Long seasonVersion,@NotNull Long selectionVersion) { }
    public record Ticket(UUID id,String state,Instant enteredAt,UUID proposalId,long version,String cancellationReason) { }
    public record Status(boolean available,List<String> blockers,Ticket ticket,SoloQueueRooms.Room room,UUID matchId,Instant serverTime,String revision) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Status status(UUID account) {
        Instant now=Instant.now();var current=eligibility.inspect(account,now);
        var latest=tickets.findByAccountIdAndSourceAndQueueTypeOrderByEnteredAtDescIdDesc(account,"FOREVER","SOLO",PageRequest.of(0,1)).stream().findFirst().orElse(null);
        var room=latest==null||latest.getProposalId()==null?null:rooms.own(account,latest.getProposalId());
        UUID match=room==null?null:roomRows.findById(room.id()).orElseThrow().getMatchId();
        var view=latest==null?null:new Ticket(latest.getId(),latest.getState(),latest.getEnteredAt(),latest.getProposalId(),latest.getVersion(),latest.getCancellationReason());
        String revision=current.blockers()+":"+(latest==null?"none":latest.getId()+":"+latest.getVersion())+":"+(room==null?"none":room.id()+":"+room.version());
        return new Status(current.blockers().isEmpty(),current.blockers(),view,room,match,now,revision);
    }
    @Transactional
    public boolean enter(UUID account,Admission request) {
        tickets.lockCoordinator();accounts.findForSelection(account).orElseThrow();
        var current=eligibility.inspect(account,Instant.now());
        if(!current.blockers().isEmpty())return false;
        if(!current.candidate().seasonId().equals(request.seasonId())||!Objects.equals(current.seasonVersion(),request.seasonVersion())
                ||!Objects.equals(current.selectionVersion(),request.selectionVersion()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Selection changed; reload Play");
        store.enqueueReal(current);return true;
    }
    @Transactional
    public void cancel(UUID account,UUID id) {
        tickets.lockCoordinator();tickets.findById(id).filter(t->t.getAccountId().equals(account)&&t.getSource().equals("FOREVER")).orElseThrow();
        store.cancel(account,id);
    }
    @Transactional
    public void respond(UUID account,UUID id,boolean accept) {
        tickets.lockCoordinator();
        var room=roomRows.findById(id).filter(r->r.getSource().equals("FOREVER")).orElseThrow();
        var roster=tickets.findByProposalIdOrderByAccountId(id);
        if(roster.stream().noneMatch(ticket->ticket.getAccountId().equals(account)))throw new NoSuchElementException();
        if(!room.getStatus().equals("PROPOSED")) {
            if(accept)return;
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Proposal is no longer awaiting acceptance");
        }
        if(!accept) { rooms.respondLocked(account,room,roster,false,Instant.now());return; }
        var participantIds=roster.stream().map(QueueTicket::getAccountId).toList();
        eligibilityRoster.lockAccounts(participantIds);
        var current=eligibility.inspectRoster(participantIds,Instant.now());
        for(var member:roster) {
            if(!SoloQueueStore.matches(member,current.get(member.getAccountId()))) {
                rooms.cancelRoom(id,"ELIGIBILITY_CHANGED");return;
            }
        }
        rooms.respondLocked(account,room,roster,true,Instant.now());
        if(room.getStatus().equals("PREPARING")&&room.getMatchId()==null) {
            var expected=proposals.findById(id).orElseThrow().proposal();
            if(PreseasonQueue.isPreview(expected))return;
            UUID match=game.createMatch(expected,Instant.now());
            var shared=matches.findById(match).filter(m->m.getMode()==MatchMode.MATCHMADE&&m.getEvidencePolicy()==MatchEvidencePolicy.FULL_ROSTER
                    &&m.getCancelledAt()==null&&!Set.of("DARKSPEAR_DIAGNOSTIC","TBC_ANNIVERSARY_WSG_TEST").contains(m.getProfile()))
                    .orElseThrow(()->new IllegalStateException("Game adapter did not create an eligible shared Match"));
            var members=assignments.findByMatchId(match);
            if(members.size()!=20)throw new IllegalStateException("Shared Match requires twenty assignments");
            for(var team:List.of(expected.teamA(),expected.teamB()))for(var player:team) {
                String key=team==expected.teamA()?"A":"B";
                if(members.stream().filter(m->m.getAccountId().equals(player.accountId())&&m.getCharacterId().equals(player.characterId())
                        &&key.equals(m.getAssignedTeam())).count()!=1)throw new IllegalStateException("Shared Match roster differs from proposal");
            }
            var season=seasons.findById(room.getSeasonId()).orElseThrow();
            ratings.freeze(match,id,season.getId(),ConfirmedRatingStore.Queue.SOLO,season.getRatingSubjectType(),expected);
            room.linkMatch(shared.getId());
        }
    }
}
