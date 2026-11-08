package io.rbgs.api.matchmaking;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Lobby mutations share the queue coordinator and group lock with admission/reservation. */
@Service @RequiredArgsConstructor
public class PremadeLobbyReservations {
    private final PremadeQueueEntryRepository entries;
    private final QueueTicketRepository tickets;

    public boolean hasFrozenRoster(UUID group) {
        return entries.hasFrozenRoster(group);
    }

    /** Caller must acquire the queue coordinator before locking the affected group(s). */
    @Transactional(propagation=Propagation.MANDATORY)
    public void beforeLockedLobbyChange(UUID group) {
        var active=entries.reservedForGroup(group);
        // Preserve both frozen rosters once a proposal exists. Decline/end the room first.
        for(var entry:active)if(tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).stream()
                .anyMatch(t->t.getState().equals("PROPOSED")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Finish or decline the proposed match before changing the lobby");
        for(var entry:active)if(entry.getState().equals("QUEUED")) {
            entry.cancel();
            tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).stream()
                    .filter(t->t.getState().equals("QUEUED")).forEach(t->t.invalidate("LOBBY_CHANGED"));
        }
        // Flush before a switch checks account exclusivity; rollback still restores every reservation.
        tickets.flush();entries.flush();
    }
}
