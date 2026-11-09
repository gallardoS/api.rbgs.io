package io.rbgs.api.matchmaking;

import io.micrometer.core.instrument.MeterRegistry;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import io.rbgs.api.groups.*;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.matches.*;
import io.rbgs.api.seasons.SeasonRepository;
import java.time.*;
import java.util.*;
import java.util.function.ToDoubleFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared transactional reservations with strict simulation/real-player source separation. */
@Service
@RequiredArgsConstructor
public class PremadeQueueStore {
    private final PremadeQueueEntryRepository entries;
    private final QueueTicketRepository tickets;
    private final QueueProposalRepository proposals;
    private final MatchRoomRepository rooms;
    private final PremadeGroupRepository groups;
    private final GroupMemberRepository members;
    private final AccountRepository accounts;
    private final MatchAssignmentRepository assignments;
    private final SeasonSelectionRepository selections;
    private final SeasonRepository seasons;
    private final MeterRegistry metrics;
    private final PremadeQueueEligibility eligibility;
    private final SoloQueueGameGateway game;

    @Transactional
    PremadeQueueEntry enqueueVerifiedSnapshot(UUID actor, PremadeQueueCandidate snapshot) {
        tickets.lockCoordinator();
        var group=groups.lockById(snapshot.groupId()).orElseThrow();
        requireCaptain(actor,group);
        lockAccounts(snapshot);
        var existing=entries.findByGroupIdAndState(snapshot.groupId(),"QUEUED").orElse(null);
        if(existing!=null) {
            if(valid(existing,Instant.now()) && existing.candidate().equals(snapshot))return existing;
            // A fresh request must never silently replace a changed roster/snapshot.
            throw new IllegalStateException("Cancel the previous group ticket before entering again");
        }
        if(!valid(snapshot,group,Instant.now()))throw new IllegalStateException("Premade is unavailable or its selection changed");
        for(var player:snapshot.members()) if(tickets.hasReservation(player.accountId()))
            throw new IllegalStateException("A participant is already queued or reserved");
        var entry=entries.saveAndFlush(new PremadeQueueEntry(snapshot));
        for(var player:snapshot.members())tickets.save(new QueueTicket(player,entry.getId()));
        tickets.flush();
        return entry;
    }

    @Transactional
    public void cancel(UUID actor, UUID entryId) {
        tickets.lockCoordinator();
        var entry=entries.findById(entryId).orElseThrow();
        var group=groups.lockById(entry.getGroupId()).orElseThrow();
        requireCaptain(actor,group);
        if(entry.getState().equals("CANCELLED"))return;
        if(!entry.getState().equals("QUEUED"))throw new IllegalStateException("Group is already reserved for a match");
        invalidate(entry);
    }

    @Transactional
    PremadeQueueEntry enqueueReal(UUID actor,PremadeQueueEligibility.Result current) {
        if(!current.blockers().isEmpty()||current.candidate()==null)throw new IllegalStateException("Premade is not eligible");
        tickets.lockCoordinator();var snapshot=current.candidate();
        var group=groups.lockById(snapshot.groupId()).orElseThrow();requireCaptain(actor,group);lockAccounts(snapshot);
        var existing=entries.findByGroupIdAndState(snapshot.groupId(),"QUEUED").orElse(null);
        if(existing!=null) {
            if(!existing.getSource().equals("FOREVER")||!completeReservations(existing)||!matches(existing,current))throw new IllegalStateException("Premade admission changed");
            return existing;
        }
        if(!valid(snapshot,group,Instant.now()))throw new IllegalStateException("Premade changed");
        for(var player:snapshot.members())if(tickets.hasReservation(player.accountId()))throw new IllegalStateException("A participant is already queued or reserved");
        var entry=entries.saveAndFlush(new PremadeQueueEntry(snapshot,"FOREVER"));
        for(var player:snapshot.members()) {
            var context=current.players().get(player.accountId());var ticket=new QueueTicket(player,entry.getId());
            ticket.realAdmission(current.seasonVersion(),context.selectionVersion(),context.deviceId());tickets.save(ticket);
        }
        tickets.flush();return entry;
    }

    @Transactional
    Optional<QueueProposal> proposeReal() {
        tickets.lockCoordinator();
        return propose(Instant.now(),PreseasonQueue.opposition(game),wait->Math.min(600,100+50*(wait.toSeconds()/60)),"FOREVER");
    }

    @Transactional
    Optional<QueueProposal> propose(Instant now, SoloQueueMatcher.GroupingPolicy opposition,
            ToDoubleFunction<Duration> mmrWindow) {
        return propose(now,opposition,mmrWindow,"SIMULATION");
    }

    private Optional<QueueProposal> propose(Instant now,SoloQueueMatcher.GroupingPolicy opposition,
            ToDoubleFunction<Duration> mmrWindow,String source) {
        long started=System.nanoTime();
        tickets.lockCoordinator();
        try {
            var waiting=entries.findBySourceAndStateOrderByEnteredAtAscIdAsc(source,"QUEUED");
            // Same group-before-account order as lobby mutations. The coordinator serializes queue workers.
            var locked=new HashMap<UUID,PremadeGroup>();
            waiting.stream().map(PremadeQueueEntry::getGroupId).distinct().sorted()
                    .forEach(id->groups.lockById(id).ifPresent(g->locked.put(id,g)));
            waiting.stream().flatMap(e->e.candidate().members().stream()).map(SoloQueueCandidate::accountId)
                    .distinct().sorted().forEach(id->accounts.findForSelection(id).orElseThrow());
            for(var entry:waiting) if(!valid(entry.candidate(),locked.get(entry.getGroupId()),now)
                    || !completeReservations(entry))invalidate(entry);
            if(source.equals("FOREVER"))for(var entry:waiting)if(entry.getState().equals("QUEUED")
                    &&!matches(entry,eligibility.inspect(entry.getGroupId(),now)))invalidate(entry);
            waiting=waiting.stream().filter(e->e.getState().equals("QUEUED")).toList();
            metrics.summary("rbgs.premade.depth").record(waiting.size());
            metrics.summary("rbgs.premade.candidates").record(waiting.size());
            for(var entry:waiting)metrics.summary("rbgs.premade.wait.seconds")
                    .record(Math.max(0,Duration.between(entry.getEnteredAt(),now).toSeconds()));
            var result=new PremadeQueueMatcher().match(waiting.stream().map(PremadeQueueEntry::candidate).toList(),now,opposition,mmrWindow);
            if(result.isEmpty())return Optional.empty();
            var match=result.get();
            var proposal=proposals.saveAndFlush(new QueueProposal(match.sharedRoom(),now));
            rooms.saveAndFlush(new MatchRoomEntity(proposal.getId(),match.teamA().seasonId(),
                    match.teamA().captainId(),match.teamB().captainId(),now,source));
            var selected=Set.of(match.teamA().groupId(),match.teamB().groupId());
            for(var entry:waiting)if(selected.contains(entry.getGroupId())) {
                entry.propose(proposal.getId());
                tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).forEach(t->t.propose(proposal.getId()));
            }
            tickets.flush();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { metrics.counter("rbgs.premade.proposals").increment(); }
                    });
            return Optional.of(proposal);
        } finally { metrics.timer("rbgs.premade.cycle").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS); }
    }

    private void lockAccounts(PremadeQueueCandidate snapshot) {
        snapshot.members().stream().map(SoloQueueCandidate::accountId).sorted().forEach(id->accounts.findForSelection(id).orElseThrow());
    }
    private static void requireCaptain(UUID actor,PremadeGroup group) {
        if(!group.getCaptainId().equals(actor))throw new SecurityException("Only the current premade captain can control this ticket");
    }
    private boolean valid(PremadeQueueEntry entry,Instant now) {
        return valid(entry.candidate(),groups.findById(entry.getGroupId()).orElse(null),now)&&completeReservations(entry);
    }
    private boolean completeReservations(PremadeQueueEntry entry) {
        var roster=tickets.findByPremadeEntryIdOrderByAccountId(entry.getId());
        return roster.size()==10&&roster.stream().allMatch(t->t.getState().equals("QUEUED")&&t.getQueueType().equals("PREMADE")
                &&t.getSource().equals(entry.getSource()))
                &&roster.stream().map(QueueTicket::getAccountId).collect(java.util.stream.Collectors.toSet())
                    .equals(entry.candidate().members().stream().map(SoloQueueCandidate::accountId).collect(java.util.stream.Collectors.toSet()));
    }
    boolean matches(PremadeQueueEntry entry,PremadeQueueEligibility.Result current) {
        if(!current.blockers().isEmpty()||current.candidate()==null)return false;
        var old=entry.candidate();var fresh=current.candidate();
        if(!old.groupId().equals(fresh.groupId())||old.revision()!=fresh.revision()||!old.captainId().equals(fresh.captainId())
                ||old.ratingSubjectType()!=fresh.ratingSubjectType())return false;
        var roster=tickets.findByPremadeEntryIdOrderByAccountId(entry.getId());
        if(roster.size()!=10)return false;
        for(var ticket:roster) {
            var player=current.players().get(ticket.getAccountId());if(player==null)return false;
            var c=player.candidate();var previous=ticket.candidate();
            // Visual roles/volunteer preferences may change; identity, game rules, device and confirmed MMR may not.
            if(!Objects.equals(ticket.getSeasonVersion(),current.seasonVersion())||!Objects.equals(ticket.getCompanionDeviceId(),player.deviceId())
                    ||!previous.characterId().equals(c.characterId())||!previous.seasonId().equals(c.seasonId())
                    ||!previous.region().equals(c.region())||!previous.ruleset().equals(c.ruleset())||!previous.groupingKey().equals(c.groupingKey())
                    ||!previous.characterClass().equals(c.characterClass())||previous.mmr()!=c.mmr())return false;
        }
        return true;
    }
    private boolean valid(PremadeQueueCandidate snapshot,PremadeGroup group,Instant now) {
        if(group==null||group.getRevision()!=snapshot.revision()||!group.getCaptainId().equals(snapshot.captainId())
                ||!group.getRegion().equals(snapshot.region())||snapshot.enteredAt().isAfter(now))return false;
        var season=seasons.findById(snapshot.seasonId()).orElse(null);
        if(season==null||season.getRatingSubjectType()!=snapshot.ratingSubjectType())return false;
        var roster=members.findByGroupIdOrderByAccountId(group.getId());
        if(!roster.stream().map(GroupMember::getAccountId).collect(java.util.stream.Collectors.toSet())
                .equals(snapshot.members().stream().map(SoloQueueCandidate::accountId).collect(java.util.stream.Collectors.toSet())))return false;
        for(var player:snapshot.members()) {
            if(accounts.findById(player.accountId()).filter(a->a.getStatus().equals("ACTIVE")).isEmpty()
                    ||assignments.hasBlockingParticipation(player.accountId(),now))return false;
            var selection=selections.findByAccountIdAndSeasonId(player.accountId(),snapshot.seasonId()).orElse(null);
            // SoloQ role, volunteer preference and visual lobby labels do not affect a premade.
            if(selection==null||!selection.getCharacterId().equals(player.characterId())
                    ||selection.getRatingSubjectType()!=snapshot.ratingSubjectType())return false;
        }
        return true;
    }
    private void invalidate(PremadeQueueEntry entry) {
        entry.cancel();
        tickets.findByPremadeEntryIdOrderByAccountId(entry.getId()).stream()
                .filter(t->t.getState().equals("QUEUED")).forEach(QueueTicket::invalidate);
    }
}
