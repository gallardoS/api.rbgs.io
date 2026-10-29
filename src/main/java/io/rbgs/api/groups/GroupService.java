package io.rbgs.api.groups;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.devices.DeviceRepository;
import io.rbgs.api.identity.*;
import io.rbgs.api.matches.MatchAssignmentRepository;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GroupService {
    private final PremadeGroupRepository groups;
    private final io.rbgs.api.characters.PlayCharacterService playCharacters;
    private final GroupMemberRepository members;
    private final GroupInvitationRepository invitations;
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final SelectionSettings settings;
    private final SeasonRepository seasons;
    private final SeasonSelectionRepository selections;
    private final CharacterIdentityRepository characters;
    private final DeviceRepository devices;
    private final MatchAssignmentRepository assignments;
    private final GroupJoinLimiter joinLimiter;
    private final PlatformTransactionManager transactionManager;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public record Member(UUID accountId, String displayName, boolean captain, String characterName, String realm,
            MatchRole role, long roleVersion, Long selectionVersion, boolean confirmed, boolean companionConnected,
            String addonStatus, String gamePresence, List<String> blockers) { }
    public record Invitation(UUID id, UUID groupId, String captainName, String recipientName, Instant expiresAt) { }
    public record Group(UUID id, UUID captainId, long revision, String region, String lobbyCode, List<Member> members, List<Invitation> invitations,
            boolean queueReady, List<String> blockers) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Group own(Authentication auth) {
        UUID actor = actor(auth);
        return members.findById(actor).map(m -> view(groups.findById(m.getGroupId()).orElseThrow(), actor)).orElse(null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Invitation> incoming(Authentication auth) {
        return invitations.findByAccountIdAndExpiresAtAfterOrderByExpiresAt(actor(auth), Instant.now()).stream()
                .map(this::invitation).toList();
    }

    @Transactional
    public Group create(Authentication auth) {
        UUID actor = actor(auth);
        lockAccount(actor);
        var existing = members.findById(actor).orElse(null);
        if (existing != null) return view(groups.findById(existing.getGroupId())
                .orElseThrow(() -> conflict("Group changed; reload the group")), actor);
        requireAvailable(actor);
        var selected = playCharacters.find(actor);
        if (selected == null) throw conflict("Select a character in Play first");
        var group = new PremadeGroup(actor, selected.region());
        newCode(group);
        groups.saveAndFlush(group);
        members.saveAndFlush(new GroupMember(actor, group.getId()));
        invitations.deleteByAccountId(actor);
        return view(group, actor);
    }

    public Group join(Authentication auth, String code) {
        UUID actor = actor(auth);
        // Consume the limit before opening the group transaction: concurrent lookups must not
        // hold all pool connections while waiting for a nested transaction's connection.
        joinLimiter.consume(actor);
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-HJ-NP-Z2-9]{8}")) throw codeUnavailable();
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return new TransactionTemplate(transactionManager).execute(status -> joinMember(actor, normalized)); }
            catch (MembershipChanged retry) { /* Re-read membership in a new transaction before locking both groups. */ }
        }
        throw conflict("Group changed; reload the group");
    }

    private Group joinMember(UUID actor, String code) {
        String hash = hash(code.strip());
        var id = groups.findIdByJoinCodeHash(hash).orElseThrow(GroupService::codeUnavailable);
        UUID sourceId = members.findGroupIdByAccountId(actor).orElse(null);
        var lockIds = new TreeSet<UUID>();
        lockIds.add(id);
        if (sourceId != null) lockIds.add(sourceId);
        var locked = new HashMap<UUID, PremadeGroup>();
        // All group locks precede the account lock; stable order prevents opposite switches deadlocking.
        for (UUID lockId : lockIds) {
            var found = groups.lockById(lockId);
            if (found.isEmpty()) {
                if (lockId.equals(id)) throw codeUnavailable();
                throw new MembershipChanged();
            }
            locked.put(lockId, found.get());
        }
        var group = locked.get(id);
        if (!hash.equals(group.getJoinCodeHash())) throw codeUnavailable();
        lockAccount(actor);
        var existing = members.findById(actor).orElse(null);
        if (!Objects.equals(existing == null ? null : existing.getGroupId(), sourceId)) throw new MembershipChanged();
        if (id.equals(sourceId)) return view(group, actor);
        requireRegion(group, actor);
        requireMatchAvailable(actor);
        if (members.countByGroupId(id) >= 10) throw conflict("This group already has ten players");
        if (existing == null) members.saveAndFlush(new GroupMember(actor, id));
        else {
            var source = locked.get(sourceId);
            boolean emptySource = false;
            if (source.getCaptainId().equals(actor)) {
                var successor = members.findFirstByGroupIdAndAccountIdNotOrderByJoinedOrderAsc(sourceId, actor);
                if (successor.isPresent()) source.transfer(successor.get().getAccountId());
                else emptySource = true;
            } else source.rosterChanged();
            existing.moveTo(id, members.nextMembershipOrder());
            members.flush();
            if (emptySource) { groups.delete(source); groups.flush(); }
        }
        invitations.deleteByAccountId(actor);
        group.rosterChanged();
        return view(group, actor);
    }

    private static class MembershipChanged extends RuntimeException { }

    @Transactional
    public Group code(Authentication auth, UUID id, long revision, boolean enabled) {
        UUID actor = actor(auth);
        var group = lockedMember(id, actor, revision);
        requireCaptain(group, actor);
        if (enabled) newCode(group); else group.code(null, null);
        return view(group, actor);
    }

    @Transactional
    public Group invite(Authentication auth, UUID id, long revision, String battleTag) {
        UUID actor = actor(auth);
        var group = lockedMember(id, actor, revision);
        requireCaptain(group, actor);
        if (members.countByGroupId(id) >= 10) throw conflict("This group already has ten players");
        var targets = accountRepository.findByDisplayNameAndStatus(battleTag.strip(), "ACTIVE");
        if (targets.size() != 1) throw conflict("Could not invite this BattleTag. Check that the player has signed in to rbgs.io");
        UUID target = targets.getFirst().getId();
        requireRegion(group, target);
        requireAvailable(target);
        var existing = invitations.findByGroupIdAndAccountId(id, target).orElse(null);
        if (existing == null) {
            if (invitations.countByGroupIdAndExpiresAtAfter(id, Instant.now()) >= 20)
                throw conflict("Cancel an invitation before inviting another player");
            invitations.saveAndFlush(new GroupInvitation(id, target));
        } else existing.renew();
        return view(group, actor);
    }

    @Transactional
    public Group accept(Authentication auth, UUID invitationId) {
        UUID actor = actor(auth);
        var initial = invitations.findById(invitationId).filter(i -> i.getAccountId().equals(actor)).orElseThrow(GroupService::notFound);
        // Lock the group before the joining account, consistent with code-based group switches.
        var group = groups.lockById(initial.getGroupId()).orElseThrow(GroupService::notFound);
        var invite = invitations.findByIdAndGroupId(invitationId, group.getId()).orElseThrow(GroupService::notFound);
        if (!Instant.now().isBefore(invite.getExpiresAt())) throw conflict("This invitation has expired");
        lockAccount(actor);
        requireRegion(group, actor);
        requireAvailable(actor);
        if (members.countByGroupId(group.getId()) >= 10) throw conflict("This group already has ten players");
        members.saveAndFlush(new GroupMember(actor, group.getId()));
        invitations.deleteByAccountId(actor);
        group.rosterChanged();
        groups.flush();
        return view(group, actor);
    }

    @Transactional
    public void decline(Authentication auth, UUID invitationId) {
        UUID actor = actor(auth);
        var invite = invitations.findById(invitationId).filter(i -> i.getAccountId().equals(actor)).orElseThrow(GroupService::notFound);
        groups.lockById(invite.getGroupId()).orElseThrow(GroupService::notFound);
        invitations.deleteInvitation(invitationId, invite.getGroupId());
    }

    @Transactional
    public Group cancelInvitation(Authentication auth, UUID id, long revision, UUID invitationId) {
        UUID actor = actor(auth);
        var group = lockedMember(id, actor, revision);
        requireCaptain(group, actor);
        var invite = invitations.findById(invitationId).filter(i -> i.getGroupId().equals(id)).orElseThrow(GroupService::notFound);
        invitations.deleteInvitation(invite.getId(), id);
        invitations.flush();
        return view(group, actor);
    }

    @Transactional
    public Group transfer(Authentication auth, UUID id, long revision, UUID target) {
        UUID actor = actor(auth);
        var group = lockedMember(id, actor, revision);
        requireCaptain(group, actor);
        if (target.equals(actor)) throw conflict("Choose another member as captain");
        members.findById(target).filter(m -> m.getGroupId().equals(id)).orElseThrow(GroupService::notFound);
        group.transfer(target);
        return view(group, actor);
    }

    @Transactional
    public Group remove(Authentication auth, UUID id, long revision, UUID target) {
        UUID actor = actor(auth);
        var group = lockedMember(id, actor, revision);
        if (!actor.equals(target)) requireCaptain(group, actor);
        var member = members.findById(target).filter(m -> m.getGroupId().equals(id)).orElseThrow(GroupService::notFound);
        if (group.getCaptainId().equals(target)) {
            var successor = members.findFirstByGroupIdAndAccountIdNotOrderByJoinedOrderAsc(id, target);
            if (successor.isEmpty()) {
                groups.delete(group);
                groups.flush();
                return null;
            }
            group.transfer(successor.get().getAccountId());
        }
        members.delete(member);
        members.flush();
        if (group.getRevision() == revision) group.rosterChanged();
        return actor.equals(target) ? null : view(group, actor);
    }

    @Transactional
    public Group displayRole(Authentication auth, UUID id, long expectedRoleVersion, MatchRole role) {
        UUID actor = actor(auth);
        var group = groups.lockById(id).orElseThrow(GroupService::notFound);
        var member = members.findById(actor).filter(m -> m.getGroupId().equals(id)).orElseThrow(GroupService::notFound);
        if (member.getDisplayRoleVersion() != expectedRoleVersion) throw conflict("Lobby role changed; reload the group");
        member.displayRole(role);
        members.flush();
        // This optional label never changes the competitive selection or roster revision.
        return view(group, actor);
    }

    @Transactional
    public Group readiness(Authentication auth, UUID id, long revision, boolean ready, UUID seasonId,
            Long seasonVersion, Long selectionVersion) {
        UUID actor = actor(auth);
        var group = lockedMember(id, actor, revision);
        lockAccount(actor);
        var member = members.findById(actor).orElseThrow(GroupService::notFound);
        if (!ready) member.unready();
        else {
            var season = currentSeason();
            if (season == null || !Objects.equals(season.getId(), seasonId)
                    || !Objects.equals(season.getVersion(), seasonVersion)) throw conflict("Season changed; reload the group");
            var selection = selections.findByAccountIdAndSeasonId(actor, season.getId())
                    .orElseThrow(() -> conflict("Save your character and role in Play first"));
            if (!Objects.equals(selection.getVersion(), selectionVersion)) throw conflict("Selection changed; reload the group");
            if (assignments.hasBlockingParticipation(actor, Instant.now())) throw conflict("Resolve your current match before preparing for another one");
            member.confirm(revision, seasonId, seasonVersion, selectionVersion);
        }
        return view(group, actor);
    }

    private Group view(PremadeGroup group, UUID actor) {
        var season = currentSeason();
        var list = members.findByGroupIdOrderByAccountId(group.getId()).stream().map(member -> member(group, member, season)).toList();
        var blockers = new ArrayList<String>();
        if (list.size() != 10) blockers.add("TEN_PLAYERS_REQUIRED");
        if (list.stream().anyMatch(m -> !m.blockers().isEmpty())) blockers.add("MEMBERS_NOT_ELIGIBLE");
        // Forever compatibility and addon setup cannot yet be established by the existing heartbeat.
        blockers.add("FOREVER_COMPATIBILITY_UNVERIFIED");
        blockers.add("MATCHMAKING_UNAVAILABLE");
        var outgoing = group.getCaptainId().equals(actor)
                ? invitations.findByGroupIdAndExpiresAtAfterOrderByExpiresAt(group.getId(), Instant.now()).stream().map(this::invitation).toList()
                : List.<Invitation>of();
        return new Group(group.getId(), group.getCaptainId(), group.getRevision(), group.getRegion(), group.getJoinCode(), list, outgoing, false, blockers);
    }

    private Member member(PremadeGroup group, GroupMember member, SeasonEntity season) {
        UUID id = member.getAccountId();
        var account = accountRepository.findById(id).orElseThrow();
        var selection = season == null ? null : selections.findByAccountIdAndSeasonId(id, season.getId()).orElse(null);
        var character = selection == null ? null : characters.findById(selection.getCharacterId()).orElse(null);
        var selected = playCharacters.find(id);
        var blockers = new ArrayList<String>();
        if (selected == null) blockers.add("PLAY_CHARACTER_REQUIRED");
        else if (!group.getRegion().equals(selected.region())) blockers.add("REGION_MISMATCH");
        if (!"ACTIVE".equals(account.getStatus())) blockers.add("ACCOUNT_UNAVAILABLE");
        if (season == null) blockers.add("SEASON_UNAVAILABLE");
        if (selection == null || character == null) blockers.add("SELECTION_REQUIRED");
        else if (selection.getRatingSubjectType() != season.getRatingSubjectType()
                || !character.getAccountId().equals(id) || !"WOW_FOREVER".equals(character.getProduct()) || !"EU".equals(character.getRegion())
                || (season.getRatingSubjectType() == RatingSubjectType.CHARACTER && character.getSource() != CharacterIdentity.Source.BLIZZARD_VERIFIED)
                || (season.getRatingSubjectType() == RatingSubjectType.ACCOUNT && character.getSource() != CharacterIdentity.Source.DECLARED))
            blockers.add("SELECTION_INVALID");
        var device = selection == null || selection.getCompanionDeviceId() == null ? null
                : devices.findById(selection.getCompanionDeviceId()).filter(d -> d.getAccountId().equals(id)).orElse(null);
        Instant now = Instant.now();
        boolean connected = device != null && device.getRevokedAt() == null && device.getRemovedAt() == null
                && device.getExpiresAt().isAfter(now) && device.getLastSeenAt() != null
                && !device.getLastSeenAt().isAfter(now) && device.getLastSeenAt().isAfter(now.minusSeconds(45));
        if (!connected) blockers.add("COMPANION_DISCONNECTED");
        if (device == null || !device.getGrantedScope().contains("evidence:write")) blockers.add("CAPTURE_PERMISSION_REQUIRED");
        blockers.add("ADDON_STATUS_UNVERIFIED");
        if (assignments.hasBlockingParticipation(id, now)) blockers.add("MATCH_UNRESOLVED");
        boolean confirmed = season != null && selection != null
                && Objects.equals(member.getReadyRevision(), group.getRevision())
                && Objects.equals(member.getReadySeasonId(), season.getId())
                && Objects.equals(member.getReadySeasonVersion(), season.getVersion())
                && Objects.equals(member.getReadySelectionVersion(), selection.getVersion());
        return new Member(id, account.getDisplayName(), group.getCaptainId().equals(id), selected != null ? selected.name() : character == null ? null : character.getName(),
                selected != null ? selected.realm() : character == null ? null : character.getRealm(), member.getDisplayRole(), member.getDisplayRoleVersion(),
                selection == null ? null : selection.getVersion(), confirmed, connected, "UNVERIFIED", "UNKNOWN", blockers);
    }

    private Invitation invitation(GroupInvitation invite) {
        var group = groups.findById(invite.getGroupId()).orElseThrow();
        return new Invitation(invite.getId(), group.getId(), accountRepository.findById(group.getCaptainId()).orElseThrow().getDisplayName(),
                accountRepository.findById(invite.getAccountId()).orElseThrow().getDisplayName(), invite.getExpiresAt());
    }
    private SeasonEntity currentSeason() {
        return settings.seasonId() == null ? null : seasons.findById(settings.seasonId()).orElse(null);
    }
    private UUID actor(Authentication auth) {
        var profile = accounts.currentUser(auth);
        if (profile == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return profile.id();
    }
    private void lockAccount(UUID id) {
        var account = accountRepository.findForSelection(id).orElseThrow(GroupService::notFound);
        if (!"ACTIVE".equals(account.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    private void requireRegion(PremadeGroup group, UUID account) {
        var selected = playCharacters.find(account);
        if (selected == null) throw conflict("This player needs to select a character in Play first");
        if (!group.getRegion().equals(selected.region())) throw conflict("This player has selected a character from a different lobby region");
    }
    private void requireAvailable(UUID id) {
        if (members.existsById(id)) throw conflict("This player already belongs to a group");
        requireMatchAvailable(id);
    }
    private void requireMatchAvailable(UUID id) {
        // Expired capture-only diagnostics are historical evidence, not an active competitive reservation.
        // Competitive pending/review results remain blocking until D01 defines earlier release.
        if (assignments.hasBlockingParticipation(id, Instant.now())) throw conflict("Resolve the current match before joining a group");
    }
    private PremadeGroup lockedMember(UUID id, UUID actor, long revision) {
        var group = groups.lockById(id).orElseThrow(GroupService::notFound);
        members.findById(actor).filter(m -> m.getGroupId().equals(id)).orElseThrow(GroupService::notFound);
        if (group.getRevision() != revision) throw conflict("Group changed; reload the group");
        return group;
    }
    private static void requireCaptain(PremadeGroup group, UUID actor) {
        if (!group.getCaptainId().equals(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the captain can do this");
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Group or invitation unavailable"); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
    private static ResponseStatusException codeUnavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "This lobby code is invalid or no longer available. Ask the captain for a current code");
    }
    private void newCode(PremadeGroup group) {
        // Serialize allocations across instances so a short-code collision can be retried before a unique violation.
        groups.lockCodeAllocation();
        for (int attempt = 0; attempt < 32; attempt++) {
            var code = new StringBuilder(8);
            for (int i = 0; i < 8; i++) code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            String value = code.toString(), hash = hash(value);
            if (hash.equals(group.getJoinCodeHash()) || groups.existsByJoinCodeHash(hash)) continue;
            group.code(value, hash);
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate a lobby code. Try again");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
