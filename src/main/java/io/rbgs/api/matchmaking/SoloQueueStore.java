package io.rbgs.api.matchmaking;

import io.micrometer.core.instrument.MeterRegistry;
import io.rbgs.api.groups.GroupMemberRepository;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.matches.MatchAssignmentRepository;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.BiPredicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal foundation only: no scheduler or HTTP route admits candidates before verified eligibility exists. */
@Service
@RequiredArgsConstructor
public class SoloQueueStore {
    private final QueueTicketRepository tickets;
    private final QueueProposalRepository proposals;
    private final io.rbgs.api.matches.MatchRoomRepository rooms;
    private final AccountRepository accounts;
    private final GroupMemberRepository groups;
    private final MatchAssignmentRepository assignments;
    private final SeasonSelectionRepository selections;
    private final MeterRegistry metrics;
    private final SoloQueueEligibility eligibility;
    private final SoloQueueGameGateway game;

    @Transactional
    QueueTicket enqueueVerifiedSnapshot(SoloQueueCandidate snapshot) {
        tickets.lockCoordinator();
        var account = accounts.findForSelection(snapshot.accountId()).orElseThrow();
        if (!"ACTIVE".equals(account.getStatus()) || groups.existsById(snapshot.accountId())
                || assignments.hasBlockingParticipation(snapshot.accountId(), Instant.now()) || !selectionMatches(snapshot))
            throw new IllegalStateException("Account is unavailable for SoloQ");
        var existing = tickets.active(snapshot.accountId());
        if (existing.isPresent()) {
            if (!existing.get().getQueueType().equals("SOLO")) throw new IllegalStateException("Account is in another queue");
            return existing.get();
        }
        return tickets.saveAndFlush(new QueueTicket(snapshot));
    }
    @Transactional
    QueueTicket enqueueReal(SoloQueueEligibility.Result eligibility) {
        if(!eligibility.blockers().isEmpty()||eligibility.candidate()==null)throw new IllegalStateException("Player is not eligible");
        tickets.lockCoordinator();accounts.findForSelection(eligibility.candidate().accountId()).orElseThrow();
        var existing=tickets.active(eligibility.candidate().accountId());
        if(existing.isPresent()) {
            if(!existing.get().getSource().equals("FOREVER")||!existing.get().getQueueType().equals("SOLO"))throw new IllegalStateException("Account is in another queue");
            return existing.get();
        }
        var ticket=new QueueTicket(eligibility.candidate());ticket.realAdmission(eligibility);
        return tickets.saveAndFlush(ticket);
    }

    @Transactional
    public void cancel(UUID accountId, UUID ticketId) {
        tickets.lockCoordinator();
        var ticket = tickets.findById(ticketId).filter(t -> t.getAccountId().equals(accountId) && t.getQueueType().equals("SOLO")).orElseThrow();
        if (ticket.getState().equals("CANCELLED")) return;
        ticket.cancel();
    }

    @Transactional
    Optional<QueueProposal> propose(Instant now, BiPredicate<String,String> verifiedOpposition) {
        return propose(()->now,verifiedOpposition);
    }

    @Transactional
    Optional<QueueProposal> proposeCurrent(BiPredicate<String,String> verifiedOpposition) {
        return propose(Instant::now,verifiedOpposition);
    }

    private Optional<QueueProposal> propose(java.util.function.Supplier<Instant> clock, BiPredicate<String,String> verifiedOpposition) {
        return propose(clock,(ruleset,a,b)->verifiedOpposition.test(a,b),"SIMULATION");
    }
    @Transactional
    Optional<QueueProposal> proposeReal() { return propose(Instant::now,PreseasonQueue.opposition(game),"FOREVER"); }
    private Optional<QueueProposal> propose(java.util.function.Supplier<Instant> clock,SoloQueueMatcher.GroupingPolicy opposition,String source) {
        long start = System.nanoTime();
        tickets.lockCoordinator();
        Instant now=clock.get();
        try {
            var waiting = tickets.findBySourceAndStateOrderByEnteredAtAscAccountIdAsc(source,"QUEUED")
                    .stream().filter(t -> t.getQueueType().equals("SOLO")).toList();
            var fresh=new HashMap<UUID,SoloQueueCandidate>();
            // Lock/revalidate account exclusivity before reservation, using the admission lock order.
            for (var ticket : waiting.stream().sorted(Comparator.comparing(QueueTicket::getAccountId)).toList()) {
                var account = accounts.findForSelection(ticket.getAccountId()).orElseThrow();
                if (!"ACTIVE".equals(account.getStatus()) || groups.existsById(ticket.getAccountId())
                        || assignments.hasBlockingParticipation(ticket.getAccountId(), now) || !selectionMatches(ticket.candidate())) ticket.cancel();
                if(source.equals("FOREVER")&&ticket.getState().equals("QUEUED")) {
                    var current=eligibility.inspect(ticket.getAccountId(),now);
                    if(!matches(ticket,current))ticket.invalidate();
                    else {
                        var c=current.candidate();var entered=ticket.candidate();
                        fresh.put(ticket.getAccountId(),new SoloQueueCandidate(c.accountId(),c.characterId(),c.seasonId(),c.region(),c.ruleset(),c.groupingKey(),
                                c.role(),c.captainConsent(),entered.enteredAt(),c.mmr(),c.characterClass(),c.priorCaptainAssignments(),
                                c.gamesPlayed(),c.visibleRating(),entered.captainLottery()));
                    }
                }
            }
            waiting = waiting.stream().filter(t -> t.getState().equals("QUEUED")).toList();
            metrics.summary("rbgs.soloq.candidates").record(waiting.size());
            metrics.summary("rbgs.soloq.depth").record(waiting.size());
            for (var ticket : waiting) metrics.summary("rbgs.soloq.wait.seconds").record(Math.max(0, now.getEpochSecond()-ticket.getEnteredAt().getEpochSecond()));
            var result = new SoloQueueMatcher().matchWithPolicy(waiting.stream().map(t->source.equals("FOREVER")?fresh.get(t.getAccountId()):t.candidate()).toList(),now,opposition);
            if (result.isEmpty()) return Optional.empty();
            var proposal = proposals.saveAndFlush(new QueueProposal(result.get(),now));
            var room=rooms.saveAndFlush(new io.rbgs.api.matches.MatchRoomEntity(proposal.getId(),result.get().teamA().getFirst().seasonId(),
                    result.get().captainA(),result.get().captainB(),now,source));
            Set<UUID> reserved = new HashSet<>();
            result.get().teamA().forEach(c -> reserved.add(c.accountId()));
            result.get().teamB().forEach(c -> reserved.add(c.accountId()));
            var roster=waiting.stream().filter(t -> reserved.contains(t.getAccountId())).toList();
            roster.forEach(t -> t.propose(proposal.getId()));
            if (PreseasonQueue.isPreview(result.get())) SoloQueueRooms.acceptTestBots(room, roster, now);
            tickets.flush();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { metrics.counter("rbgs.soloq.proposals").increment(); }
                    });
            return Optional.of(proposal);
        } finally { metrics.timer("rbgs.soloq.cycle").record(System.nanoTime()-start, java.util.concurrent.TimeUnit.NANOSECONDS); }
    }

    private boolean selectionMatches(SoloQueueCandidate candidate) {
        return selections.findByAccountIdAndSeasonId(candidate.accountId(), candidate.seasonId())
                .filter(s -> s.getCharacterId().equals(candidate.characterId()) && s.getMatchRole() == candidate.role()
                        && s.isCaptainConsent() == candidate.captainConsent()).isPresent();
    }
    static boolean matches(QueueTicket ticket,SoloQueueEligibility.Result current) {
        if(!current.blockers().isEmpty()||current.candidate()==null)return false;
        var old=ticket.candidate();var c=current.candidate();
        return Objects.equals(ticket.getSeasonVersion(),current.seasonVersion())&&Objects.equals(ticket.getSelectionVersion(),current.selectionVersion())
                &&Objects.equals(ticket.getCompanionDeviceId(),current.deviceId())&&old.accountId().equals(c.accountId())
                &&old.characterId().equals(c.characterId())&&old.seasonId().equals(c.seasonId())&&old.role()==c.role()
                &&old.captainConsent()==c.captainConsent()&&old.region().equals(c.region())&&old.ruleset().equals(c.ruleset())
                &&old.groupingKey().equals(c.groupingKey())&&old.characterClass().equals(c.characterClass());
    }
}
