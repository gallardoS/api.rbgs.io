package io.rbgs.api.matchmaking;

import io.rbgs.api.matches.*;
import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.entity.AccountEntity;
import io.rbgs.api.ratings.RatingResults;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SoloQueueRooms {
    private final QueueTicketRepository tickets;
    private final QueueProposalRepository proposals;
    private final MatchRoomRepository rooms;
    private final AccountRepository accounts;
    private final MatchRepository matches;
    private final io.rbgs.api.characters.selection.CharacterIdentityRepository characters;
    private final RatingResults ratings;

    public record Member(UUID accountId,String name,String role,boolean captain,boolean accepted,boolean testBot) { }
    public record Room(UUID id,String source,String status,Instant acceptanceDeadline,Instant preparationDeadline,
            String cancellationReason,long version,List<Member> teamA,List<Member> teamB,boolean preseasonPreview) { }

    static void acceptTestBots(MatchRoomEntity room, List<QueueTicket> roster, Instant now) {
        if (roster.stream().noneMatch(QueueTicket::isTestBot)) return;
        if (roster.stream().allMatch(QueueTicket::isTestBot))
            throw new IllegalStateException("A public rehearsal requires a real participant");
        for (var ticket : roster) if (ticket.isTestBot()) {
            ticket.accept(now);
            room.acceptanceChanged();
        }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Room own(UUID account,UUID id) {
        var roster=tickets.findByProposalIdOrderByAccountId(id);
        if (roster.stream().noneMatch(t->t.getAccountId().equals(account))) throw new NoSuchElementException();
        var room=rooms.findById(id).orElseThrow();
        var proposal=proposals.findById(id).orElseThrow().proposal();
        boolean real = room.getSource().equals("FOREVER");
        var players = Stream.concat(proposal.teamA().stream(), proposal.teamB().stream()).toList();
        Map<UUID, String> names = real
                ? characters.findAllById(players.stream().map(SoloQueueCandidate::characterId).toList()).stream()
                        .collect(Collectors.toMap(CharacterIdentity::getId, character -> character.getName() + " · " + character.getRealm()))
                : accounts.findAllById(players.stream().map(SoloQueueCandidate::accountId).toList()).stream()
                        .collect(Collectors.toMap(AccountEntity::getId, AccountEntity::getDisplayName));
        return new Room(id,room.getSource(),room.getStatus(),room.getAcceptanceDeadline(),room.getPreparationDeadline(),
                room.getCancellationReason(),room.getVersion(),members(proposal.teamA(),proposal.captainA(),roster,real,names),
                members(proposal.teamB(),proposal.captainB(),roster,real,names),PreseasonQueue.isPreview(proposal));
    }
    private List<Member> members(List<SoloQueueCandidate> team, UUID captain, List<QueueTicket> tickets, boolean real, Map<UUID, String> names) {
        return team.stream().map(c->new Member(c.accountId(),real ? names.getOrDefault(c.characterId(), "Player")
                : Optional.ofNullable(names.get(c.accountId())).orElseThrow(),
                c.role().name(),captain.equals(c.accountId()),tickets.stream().filter(t->t.getAccountId().equals(c.accountId()))
                    .anyMatch(t->t.getAcceptedAt()!=null), tickets.stream()
                    .anyMatch(t -> t.isTestBot() && t.getAccountId().equals(c.accountId())))).toList();
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
        var room=rooms.findById(id).orElseThrow();
        respondLocked(account, room, roster, accept, now);
    }

    // The live queue services already hold the coordinator lock and have loaded the room and roster.
    void respondLocked(UUID account, MatchRoomEntity room, List<QueueTicket> roster, boolean accept, Instant now) {
        var ticket=requireParticipant(account, roster);
        if (room.due(now)) { cancel(room,roster,expirationReason(room)); return; }
        if (!Set.of("PROPOSED","PREPARING").contains(room.getStatus())) return;
        if (!accept) {
            // The SoloQ lab also uses this action to end an already prepared simulation.
            boolean endingSimulation = room.getSource().equals("SIMULATION") && room.getStatus().equals("PREPARING");
            if (ticket.getAcceptedAt() != null && !endingSimulation) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already accepted this proposal");
            }
            cancel(room,roster,"PARTICIPANT_DECLINED");
            return;
        }
        if (!room.getStatus().equals("PROPOSED") || ticket.getAcceptedAt()!=null) return;
        if (!accounts.findForSelection(account).orElseThrow().getStatus().equals("ACTIVE")) {
            cancel(room,roster,"ACCOUNT_UNAVAILABLE"); return;
        }
        ticket.accept(now);
        // Force a dirty field rather than assigning JPA's managed optimistic version.
        room.acceptanceChanged();
        if (roster.size()!=20) throw new IllegalStateException("A room requires exactly twenty participants");
        if (roster.stream().allMatch(t->t.getAcceptedAt()!=null)) {
            if (isPreview(room)) room.preparing(now,60);
            else room.preparing(now);
        }
    }

    private static QueueTicket requireParticipant(UUID account, List<QueueTicket> roster) {
        return roster.stream().filter(ticket -> ticket.getAccountId().equals(account)).findFirst().orElseThrow();
    }
    @Transactional
    public void expire(Instant now) {
        tickets.lockCoordinator();
        for (var room:rooms.findByStatusIn(List.of("PROPOSED","PREPARING")))
            if (room.due(now)) cancel(room,tickets.findByProposalIdOrderByAccountId(room.getId()),"DEADLINE_EXPIRED");
    }
    private void cancel(MatchRoomEntity room,List<QueueTicket> roster,String reason) {
        room.cancel(reason);
        if(room.getMatchId()!=null) {
            var match=matches.lockById(room.getMatchId()).orElseThrow();
            match.cancel(Instant.now());
            if (ratings.enabled()) {
                matches.flush();
                ratings.synchronize(match);
            }
        }
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
            if(room.due(now))cancel(room,tickets.findByProposalIdOrderByAccountId(room.getId()),expirationReason(room));
    }

    private boolean isPreview(MatchRoomEntity room) {
        return room.getSource().equals("FOREVER") && PreseasonQueue.SEASON_ID.equals(room.getSeasonId())
                && PreseasonQueue.isPreview(proposals.findById(room.getId()).orElseThrow().proposal());
    }

    private String expirationReason(MatchRoomEntity room) {
        return room.getStatus().equals("PREPARING") && isPreview(room) ? PreseasonQueue.COMPLETED : "DEADLINE_EXPIRED";
    }
}
