package io.rbgs.api.matchmaking;

import io.rbgs.api.matches.*;
import io.rbgs.api.identity.AccountRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class SoloQueueRooms {
    private final QueueTicketRepository tickets;
    private final QueueProposalRepository proposals;
    private final MatchRoomRepository rooms;
    private final AccountRepository accounts;
    private final MatchRepository matches;
    private final io.rbgs.api.characters.selection.CharacterIdentityRepository characters;

    public record Member(UUID accountId,String name,String role,boolean captain,boolean accepted) { }
    public record Room(UUID id,String source,String status,Instant acceptanceDeadline,Instant preparationDeadline,
            String cancellationReason,long version,List<Member> teamA,List<Member> teamB) { }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Room own(UUID account,UUID id) {
        var roster=tickets.findByProposalIdOrderByAccountId(id);
        if (roster.stream().noneMatch(t->t.getAccountId().equals(account))) throw new NoSuchElementException();
        var room=rooms.findById(id).orElseThrow();
        var proposal=proposals.findById(id).orElseThrow().proposal();
        return new Room(id,room.getSource(),room.getStatus(),room.getAcceptanceDeadline(),room.getPreparationDeadline(),
                room.getCancellationReason(),room.getVersion(),members(proposal.teamA(),proposal.captainA(),roster,room.getSource()),
                members(proposal.teamB(),proposal.captainB(),roster,room.getSource()));
    }
    private List<Member> members(List<SoloQueueCandidate> team,UUID captain,List<QueueTicket> tickets,String source) {
        return team.stream().map(c->new Member(c.accountId(),source.equals("FOREVER")?characters.findById(c.characterId())
                .map(p->p.getName()+" · "+p.getRealm()).orElse("Player"):accounts.findById(c.accountId()).orElseThrow().getDisplayName(),
                c.role().name(),captain.equals(c.accountId()),tickets.stream().filter(t->t.getAccountId().equals(c.accountId()))
                    .anyMatch(t->t.getAcceptedAt()!=null))).toList();
    }

    @Transactional
    public void respond(UUID account,UUID id,boolean accept) {
        respond(account,id,accept,Instant::now);
    }
    @Transactional
    void respondAt(UUID account,UUID id,boolean accept,Instant now) {
        respond(account,id,accept,()->now);
    }
    private void respond(UUID account,UUID id,boolean accept,java.util.function.Supplier<Instant> time) {
        tickets.lockCoordinator();
        Instant now=time.get();
        var roster=tickets.findByProposalIdOrderByAccountId(id);
        var ticket=roster.stream().filter(t->t.getAccountId().equals(account)).findFirst().orElseThrow();
        var room=rooms.findById(id).orElseThrow();
        if (room.due(now)) { cancel(room,roster,"DEADLINE_EXPIRED"); return; }
        if (room.getStatus().equals("CANCELLED")) return;
        if (!accept) { cancel(room,roster,"PARTICIPANT_DECLINED"); return; }
        if (!room.getStatus().equals("PROPOSED") || ticket.getAcceptedAt()!=null) return;
        if (!accounts.findForSelection(account).orElseThrow().getStatus().equals("ACTIVE")) {
            cancel(room,roster,"ACCOUNT_UNAVAILABLE"); return;
        }
        ticket.accept(now);
        // Force a dirty field rather than assigning JPA's managed optimistic version.
        room.acceptanceChanged();
        if (roster.size()!=20) throw new IllegalStateException("A room requires exactly twenty participants");
        if (roster.stream().allMatch(t->t.getAcceptedAt()!=null)) room.preparing(now);
    }
    @Transactional
    public void expire(Instant now) {
        tickets.lockCoordinator();
        for (var room:rooms.findByStatusIn(List.of("PROPOSED","PREPARING")))
            if (room.due(now)) cancel(room,tickets.findByProposalIdOrderByAccountId(room.getId()),"DEADLINE_EXPIRED");
    }
    private void cancel(MatchRoomEntity room,List<QueueTicket> roster,String reason) {
        room.cancel(reason);
        if(room.getMatchId()!=null)matches.lockById(room.getMatchId()).orElseThrow().cancel(Instant.now());
        roster.stream().filter(t->t.getState().equals("PROPOSED")).forEach(QueueTicket::releaseRoom);
    }
    @Transactional
    public void cancelRoom(UUID id,String reason) {
        tickets.lockCoordinator();var room=rooms.findById(id).orElseThrow();
        if(!room.getStatus().equals("CANCELLED"))cancel(room,tickets.findByProposalIdOrderByAccountId(id),reason);
    }
    @Transactional
    public void expireReal(Instant now) {
        tickets.lockCoordinator();
        for(var room:rooms.findBySourceAndStatusIn("FOREVER",List.of("PROPOSED","PREPARING")))
            if(room.due(now))cancel(room,tickets.findByProposalIdOrderByAccountId(room.getId()),"DEADLINE_EXPIRED");
    }
}
