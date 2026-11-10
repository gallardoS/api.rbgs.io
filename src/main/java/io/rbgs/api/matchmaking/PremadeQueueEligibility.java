package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.groups.*;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Real, server-resolved per-member eligibility. Visual roles and SoloQ volunteer preferences are irrelevant. */
@Service @RequiredArgsConstructor
public class PremadeQueueEligibility {
    private final SelectionSettings settings;
    private final SeasonRepository seasons;
    private final QueueEligibilityRoster inputs;
    private final PremadeGroupRepository groups;
    private final GroupMemberRepository members;
    private final SoloQueueGameGateway game;
    private final ConfirmedRatingStore ratings;
    private final PreseasonQueue preseason;
    public record Player(Long selectionVersion,UUID deviceId,SoloQueueCandidate candidate) { }
    public record Result(List<String> blockers,PremadeQueueCandidate candidate,Long seasonVersion,Map<UUID,Player> players) { }

    public Result inspect(UUID groupId,Instant now) {
        var blockers=new LinkedHashSet<String>();var players=new LinkedHashMap<UUID,Player>();
        var group=groupId==null?null:groups.findById(groupId).orElse(null);
        var roster=group==null?List.<GroupMember>of():members.findByGroupIdOrderByAccountId(groupId);
        if(group==null)blockers.add("GROUP_REQUIRED");
        else if(roster.size()!=10||roster.stream().noneMatch(m->m.getAccountId().equals(group.getCaptainId())))blockers.add("TEN_PLAYERS_REQUIRED");
        var season=settings.seasonId()==null?null:seasons.findById(settings.seasonId()).orElse(null);
        if(season==null)blockers.add("SEASON_UNAVAILABLE");
        else if(season.getStartedAt()==null||season.getStartedAt().isAfter(now))blockers.add("SEASON_NOT_STARTED");
        boolean preview=preseason.active(season);
        var current = inputs.load(roster.stream().map(GroupMember::getAccountId).toList(), season, now);
        var classes = preview ? preseason.selectedClasses(QueueEligibilityRoster.characters(current)) : Map.<UUID, String>of();
        var confirmed = season == null ? Map.<UUID, ConfirmedRatingStore.Snapshot>of()
                : ratings.confirmedForRoster(season.getId(), ConfirmedRatingStore.Queue.PREMADE, season.getRatingSubjectType(),
                        QueueEligibilityRoster.subjects(current, season));
        for(var member:roster) {
            UUID account=member.getAccountId();
            var player=current.get(account);
            var accountRow=player.activeAccount();
            if(accountRow==null)blockers.add("ACCOUNT_UNAVAILABLE");
            boolean bot=preview&&accountRow!=null&&PreseasonQueue.BOT_ISSUER.equals(accountRow.getProviderIssuer())&&preseason.botActive(account,now);
            if(player.blockingMatch())blockers.add("MATCH_UNRESOLVED");
            var selection=player.selection();
            var character=player.character();
            if(selection==null||character==null)blockers.add("SELECTION_REQUIRED");
            else if(!character.getAccountId().equals(account)||!character.getProduct().equals("WOW_FOREVER")||!character.getRegion().equals(group.getRegion())
                    ||!character.getRegion().equals("EU")||selection.getRatingSubjectType()!=season.getRatingSubjectType()
                    ||season.getRatingSubjectType()==RatingSubjectType.CHARACTER&&character.getSource()!=CharacterIdentity.Source.BLIZZARD_VERIFIED
                    ||season.getRatingSubjectType()==RatingSubjectType.ACCOUNT&&character.getSource()!=CharacterIdentity.Source.DECLARED)
                blockers.add("SELECTION_INVALID");
            var device=player.device();
            if(!bot&&(device==null||!device.getAccountId().equals(account)||device.getRevokedAt()!=null||device.getRemovedAt()!=null
                    ||!device.getExpiresAt().isAfter(now)||device.getLastSeenAt()==null||device.getLastSeenAt().isAfter(now)
                    ||!device.getLastSeenAt().isAfter(now.minusSeconds(45))))blockers.add("COMPANION_DISCONNECTED");
            if(!bot&&(device==null||!Arrays.asList(device.getGrantedScope().split(" ")).contains("evidence:write")))blockers.add("CAPTURE_PERMISSION_REQUIRED");
            SoloQueueGameGateway.VerifiedPlayer proof=null;ConfirmedRatingStore.Snapshot rating=null;
            String previewClass=null;
            if(season!=null&&selection!=null&&character!=null) {
                if(preview) {
                    previewClass=classes.get(character.getId());
                    if(previewClass==null)blockers.add("SELECTION_INVALID");
                } else proof=game.inspect(account,season,selection).orElse(null);
                if(proof!=null&&(!account.equals(proof.accountId())||!season.getId().equals(proof.seasonId())||season.getVersion()!=proof.seasonVersion()
                        ||!character.getId().equals(proof.characterId())||selection.getVersion()!=proof.selectionVersion()
                        ||!Objects.equals(selection.getCompanionDeviceId(),proof.deviceId())||!"WOW_FOREVER".equals(proof.product())
                        ||!"EU".equals(proof.region())||!group.getRegion().equals(proof.region())
                        ||proof.observedAt()==null||proof.observedAt().isAfter(now)||proof.observedAt().isBefore(now.minusSeconds(45))
                        ||proof.validUntil()==null||!proof.validUntil().isAfter(now)
                        ||proof.ruleset()==null||proof.ruleset().isBlank()||proof.groupingKey()==null||proof.groupingKey().isBlank()
                        ||proof.characterClass()==null||proof.characterClass().isBlank()))proof=null;
                UUID subject=season.getRatingSubjectType()==RatingSubjectType.ACCOUNT?account:character.getId();
                rating=bot?new ConfirmedRatingStore.Snapshot(season.getId(),season.getRatingSubjectType(),subject,1500,0,0,0,0)
                        :confirmed.get(subject);
            }
            if(!preview&&proof==null)blockers.addAll(List.of("FOREVER_COMPATIBILITY_UNVERIFIED","ADDON_STATUS_UNVERIFIED","GAME_PRESENCE_UNVERIFIED"));
            if(rating==null)blockers.add("PREMADE_COMPETITIVE_DATA_UNAVAILABLE");
            if((proof!=null||previewClass!=null)&&rating!=null)players.put(account,new Player(selection.getVersion(),bot?null:selection.getCompanionDeviceId(),
                    new SoloQueueCandidate(account,character.getId(),season.getId(),character.getRegion(),
                            preview?PreseasonQueue.RULESET:proof.ruleset(),preview?PreseasonQueue.GROUPING_KEY:proof.groupingKey(),
                            member.getDisplayRole(),false,now,rating.mmr(),preview?previewClass:proof.characterClass(),0)));
        }
        if(!preview&&!game.handoffAvailable())blockers.add("MATCH_ROOM_UNAVAILABLE");
        PremadeQueueCandidate candidate=null;
        if(blockers.isEmpty()) {
            try { candidate=new PremadeQueueCandidate(groupId,group.getRevision(),group.getCaptainId(),season.getRatingSubjectType(),
                    players.values().stream().map(Player::candidate).toList()); }
            catch(IllegalArgumentException incompatible) { blockers.add("GROUPING_INCOMPATIBLE"); }
        }
        return new Result(List.copyOf(blockers),candidate,season==null?null:season.getVersion(),Map.copyOf(players));
    }
}
