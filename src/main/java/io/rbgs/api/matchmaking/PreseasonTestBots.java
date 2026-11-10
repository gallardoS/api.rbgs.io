package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.characters.selection.CharacterIdentityRepository;
import io.rbgs.api.characters.selection.MatchRole;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import io.rbgs.api.groups.GroupMember;
import io.rbgs.api.groups.GroupMemberRepository;
import io.rbgs.api.groups.PremadeGroup;
import io.rbgs.api.groups.PremadeGroupRepository;
import io.rbgs.api.identity.AccountAuthorizationManager;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.seasons.RatingSubjectType;
import io.rbgs.api.seasons.SeasonRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class PreseasonTestBots {
    enum Queue { SOLO, PREMADE }
    record Status(boolean available, boolean running, boolean owned) {}
    private record Run(UUID owner, Instant expiresAt, UUID ownGroup, UUID rivalGroup, UUID hostTicket) {}
    private static final String REALM = "Preseason";

    private final JdbcTemplate db;
    private final AccountService accounts;
    private final AccountRepository accountRows;
    private final CharacterIdentityRepository characters;
    private final SeasonSelectionRepository selections;
    private final SeasonRepository seasons;
    private final PremadeGroupRepository groups;
    private final GroupMemberRepository members;
    private final QueueTicketRepository tickets;
    private final PremadeQueueEntryRepository entries;
    private final SoloQueueEligibility soloEligibility;
    private final PremadeQueueEligibility premadeEligibility;
    private final SoloQueueStore solo;
    private final PremadeQueueStore premade;
    private final PremadeLobbyReservations reservations;
    private final PreseasonQueue preseason;

    Status status(UUID owner) {
        var run = run();
        return new Status(enabled(), run != null, run != null && owner.equals(run.owner()));
    }

    @Transactional
    Status start(UUID owner, Queue queue) {
        tickets.lockCoordinator();
        if (!enabled()) throw conflict("Preseason queue preview is disabled");
        if (run() != null) throw conflict("A rehearsal is already running");
        var now = Instant.now();
        if (queue == Queue.SOLO) {
            var host = tickets.active(owner).filter(t -> waiting(t, Queue.SOLO))
                    .orElseThrow(() -> conflict("Start searching for a SoloQ match in Play first"));
            var current = soloEligibility.inspect(owner, now);
            if (!SoloQueueStore.matches(host, current)) throw conflict("SoloQ eligibility changed");
            insertRun(owner, now, null, null, host.getId());
            var roles = new ArrayList<MatchRole>();
            var composition = SoloQueueMatcher.compositions(java.time.Duration.ZERO).getFirst();
            for (var role : MatchRole.values()) {
                int count = 2 * composition.count(role) - (role == current.candidate().role() ? 1 : 0);
                for (int i = 0; i < count; i++) roles.add(role);
            }
            for (int i = 0; i < roles.size(); i++) {
                var bot = prepareBot(i + 1, roles.get(i));
                solo.enqueueReal(soloEligibility.inspect(bot, Instant.now()));
            }
        } else {
            var own = members.findGroupIdByAccountId(owner).flatMap(groups::lockById)
                    .filter(g -> g.getCaptainId().equals(owner) && "EU".equals(g.getRegion()))
                    .orElseThrow(() -> conflict("Create or lead an EU Premade lobby in Play first"));
            if (tickets.hasReservation(owner) || !entries.reservedForGroup(own.getId()).isEmpty())
                throw conflict("Finish the current search before adding Premade bots");
            int openSlots = 10 - Math.toIntExact(members.countByGroupId(own.getId()));
            if (openSlots < 0 || openSlots > 9) throw conflict("Invalid Premade roster");
            for (int i = 1; i <= openSlots; i++)
                members.save(new GroupMember(prepareBot(i, MatchRole.DPS), own.getId()));
            if (openSlots > 0) groups.lockById(own.getId()).orElseThrow().rosterChanged();
            UUID captain = prepareBot(10, MatchRole.DPS);
            var rival = groups.saveAndFlush(new PremadeGroup(captain, "EU"));
            members.save(new GroupMember(captain, rival.getId()));
            for (int i = 11; i <= 19; i++)
                members.save(new GroupMember(prepareBot(i, MatchRole.DPS), rival.getId()));
            insertRun(owner, now, own.getId(), rival.getId(), null);
        }
        return new Status(true, true, true);
    }

    @Transactional
    Status stop(UUID owner) {
        tickets.lockCoordinator();
        var run = run();
        if (run != null && owner.equals(run.owner())) retire(run, Instant.now());
        return status(owner);
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    void maintain() {
        if (run() == null) return;
        tickets.lockCoordinator();
        var run = run();
        if (run == null) return;
        var now = Instant.now();
        if (!enabled() || !now.isBefore(run.expiresAt())
                || accountRows.findById(run.owner()).filter(a -> "ACTIVE".equals(a.getStatus())
                        && AccountAuthorizationManager.allowsRole(a.getRole(), "TESTER")).isEmpty()) {
            retire(run, now);
            return;
        }
        if (botProposed()) {
            retire(run, now);
            return;
        }
        if (run.hostTicket() != null) {
            if (tickets.findById(run.hostTicket()).filter(t -> t.getState().equals("QUEUED")).isEmpty()) retire(run, now);
            return;
        }

        var own = groups.lockById(run.ownGroup()).filter(g -> g.getCaptainId().equals(run.owner())).orElse(null);
        var rival = groups.lockById(run.rivalGroup()).orElse(null);
        if (own == null || rival == null) {
            retire(run, now);
            return;
        }
        var host = tickets.active(run.owner()).filter(t -> waiting(t, Queue.PREMADE))
                .filter(t -> entries.findById(t.getPremadeEntryId()).filter(e -> own.getId().equals(e.getGroupId())).isPresent())
                .orElse(null);
        if (host != null) {
            var current = premadeEligibility.inspect(rival.getId(), now);
            if (!current.blockers().isEmpty()) {
                retire(run, now);
                return;
            }
            premade.enqueueReal(rival.getCaptainId(), current);
            db.update("UPDATE rbgs.preseason_bot_run SET host_ticket_id = ? WHERE id = 1", host.getId());
        }
    }

    private void retire(Run run, Instant now) {
        db.update("UPDATE rbgs.preseason_bot_run SET expires_at = least(expires_at, ?) WHERE id = 1", Timestamp.from(now));
        var waiting = tickets.findBySourceAndStateOrderByEnteredAtAscAccountIdAsc("FOREVER", "QUEUED");
        waiting.stream().filter(t -> t.isTestBot() && t.getQueueType().equals("SOLO")).forEach(QueueTicket::cancel);
        if (run.rivalGroup() != null)
            groups.lockById(run.rivalGroup()).filter(rival -> !reservations.hasFrozenRoster(rival.getId()))
                    .ifPresent(rival -> reservations.beforeLockedLobbyChange(rival.getId()));
        if (botProposed()) return;

        if (run.ownGroup() != null) groups.lockById(run.ownGroup()).ifPresent(own -> {
            var bots = members.findByGroupIdOrderByAccountId(own.getId()).stream()
                    .filter(m -> accountRows.findById(m.getAccountId())
                            .filter(a -> PreseasonQueue.BOT_ISSUER.equals(a.getProviderIssuer())).isPresent()).toList();
            if (!bots.isEmpty()) {
                reservations.beforeLockedLobbyChange(own.getId());
                members.deleteAll(bots);
                own.rosterChanged();
            }
        });
        if (run.rivalGroup() != null) groups.findById(run.rivalGroup()).ifPresent(groups::delete);
        db.update("DELETE FROM rbgs.preseason_bot_run WHERE id = 1");
    }

    private boolean botProposed() {
        return tickets.existsByTestBotTrueAndState("PROPOSED");
    }

    private boolean enabled() {
        return preseason.active(seasons.findById(PreseasonQueue.SEASON_ID).orElse(null));
    }

    private boolean waiting(QueueTicket ticket, Queue queue) {
        return ticket.getSeasonId().equals(PreseasonQueue.SEASON_ID) && ticket.getSource().equals("FOREVER")
                && ticket.getQueueType().equals(queue.name()) && ticket.getState().equals("QUEUED");
    }

    private void insertRun(UUID owner, Instant now, UUID own, UUID rival, UUID hostTicket) {
        db.update("""
                INSERT INTO rbgs.preseason_bot_run (id, owner_id, expires_at, own_group_id, rival_group_id, host_ticket_id)
                VALUES (1, ?, ?, ?, ?, ?)
                """, owner, Timestamp.from(now.plusSeconds(180)), own, rival, hostTicket);
    }

    private Run run() {
        return db.query("SELECT * FROM rbgs.preseason_bot_run WHERE id = 1", (row, index) ->
                new Run(row.getObject("owner_id", UUID.class), row.getTimestamp("expires_at").toInstant(),
                        row.getObject("own_group_id", UUID.class), row.getObject("rival_group_id", UUID.class),
                        row.getObject("host_ticket_id", UUID.class))).stream().findFirst().orElse(null);
    }

    private UUID prepareBot(int index, MatchRole role) {
        String subject = Integer.toString(index);
        String name = "Test Bot " + index;
        UUID account = accountRows.findByProviderIssuerAndProviderSubject(PreseasonQueue.BOT_ISSUER, subject)
                .map(a -> a.getId()).orElseGet(() -> accounts.upsert(PreseasonQueue.BOT_ISSUER, subject, name).id());
        db.update("""
                INSERT INTO rbgs.play_characters(account_id, namespace, wow_account_id, realm_id, character_id, region, name, realm)
                VALUES (?, 'profile-forever-eu', 1, 1, ?, 'EU', ?, ?) ON CONFLICT (account_id) DO NOTHING
                """, account, index, name, REALM);
        var selection = selections.findByAccountIdAndSeasonId(account, PreseasonQueue.SEASON_ID).orElseGet(() ->
                new SeasonSelection(account, PreseasonQueue.SEASON_ID));
        UUID character = selection.getCharacterId();
        if (character == null)
            character = characters.saveAndFlush(CharacterIdentity.declared(account, name, REALM)).getId();
        selection.choose(character, RatingSubjectType.ACCOUNT, role, false);
        selections.saveAndFlush(selection);
        return account;
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
