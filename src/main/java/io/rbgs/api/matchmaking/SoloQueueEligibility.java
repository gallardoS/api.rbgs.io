package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.groups.GroupMemberRepository;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class SoloQueueEligibility {
    private final SelectionSettings settings;
    private final SeasonRepository seasons;
    private final QueueEligibilityRoster roster;
    private final GroupMemberRepository groups;
    private final SoloQueueGameGateway game;
    private final SoloQueueCompetitiveData competitive;
    private final PreseasonQueue preseason;
    public record Result(List<String> blockers,SoloQueueCandidate candidate,Long seasonVersion,Long selectionVersion,UUID deviceId) { }

    public Result inspect(UUID account,Instant now) {
        return inspectRoster(List.of(account), now).get(account);
    }

    Map<UUID, Result> inspectRoster(Collection<UUID> accounts, Instant now) {
        if (accounts.isEmpty()) return Map.of();
        var season = settings.seasonId() == null ? null : seasons.findById(settings.seasonId()).orElse(null);
        var players = roster.load(accounts, season, now);
        var grouped = groups.findAllById(accounts).stream().map(member -> member.getAccountId())
                .collect(java.util.stream.Collectors.toSet());
        boolean preview = preseason.active(season);
        var classes = preview ? preseason.selectedClasses(QueueEligibilityRoster.characters(players)) : Map.<UUID, String>of();
        var ratings = season == null ? Map.<UUID, SoloQueueCompetitiveData.Snapshot>of()
                : competitive.confirmedForRoster(season.getId(), season.getRatingSubjectType(), QueueEligibilityRoster.subjects(players, season));
        Map<UUID, Result> results = new HashMap<>();
        for (UUID account : accounts)
            results.put(account, inspect(account, now, season, players.get(account), grouped.contains(account), preview, classes, ratings));
        return results;
    }

    private Result inspect(UUID account, Instant now, SeasonEntity season, QueueEligibilityRoster.Player player,
            boolean grouped, boolean preview, Map<UUID, String> classes, Map<UUID, SoloQueueCompetitiveData.Snapshot> ratings) {
        var blockers=new ArrayList<String>();
        var accountRow=player.activeAccount();
        if(accountRow==null)blockers.add("ACCOUNT_UNAVAILABLE");
        if(grouped)blockers.add("GROUP_MEMBERSHIP_EXISTS");
        if(player.blockingMatch())blockers.add("MATCH_UNRESOLVED");
        var selection=player.selection();
        if(season==null)blockers.add("SEASON_UNAVAILABLE");
        else if(season.getStartedAt()==null||season.getStartedAt().isAfter(now))blockers.add("SEASON_NOT_STARTED");
        var character=player.character();
        if(selection==null||character==null)blockers.add("SELECTION_REQUIRED");
        else if(selection.getMatchRole()==null||!character.getAccountId().equals(account)||!character.getProduct().equals("WOW_FOREVER")||!character.getRegion().equals("EU")
                ||selection.getRatingSubjectType()!=season.getRatingSubjectType()
                ||season.getRatingSubjectType()==RatingSubjectType.CHARACTER&&character.getSource()!=CharacterIdentity.Source.BLIZZARD_VERIFIED
                ||season.getRatingSubjectType()==RatingSubjectType.ACCOUNT&&character.getSource()!=CharacterIdentity.Source.DECLARED)
            blockers.add("SELECTION_INVALID");
        var device=player.device();
        boolean connected=device!=null&&device.getAccountId().equals(account)&&device.getRevokedAt()==null&&device.getRemovedAt()==null
                &&device.getExpiresAt().isAfter(now)&&device.getLastSeenAt()!=null&&!device.getLastSeenAt().isAfter(now)
                &&device.getLastSeenAt().isAfter(now.minusSeconds(45));
        boolean bot=preview&&accountRow!=null&&PreseasonQueue.BOT_ISSUER.equals(accountRow.getProviderIssuer())&&preseason.botActive(account,now);
        if(!bot&&!connected)blockers.add("COMPANION_DISCONNECTED");
        if(!bot&&(device==null||!Arrays.asList(device.getGrantedScope().split(" ")).contains("evidence:write")))blockers.add("CAPTURE_PERMISSION_REQUIRED");
        SoloQueueGameGateway.VerifiedPlayer proof=null;
        SoloQueueCompetitiveData.Snapshot values=null;
        String previewClass=null;
        if(season!=null&&selection!=null&&character!=null) {
            if(preview) {
                previewClass=classes.get(character.getId());
                if(previewClass==null&&!blockers.contains("SELECTION_INVALID"))blockers.add("SELECTION_INVALID");
            } else proof=game.inspect(account,season,selection).orElse(null);
            if(proof!=null && (!account.equals(proof.accountId())||!season.getId().equals(proof.seasonId())
                    ||season.getVersion()!=proof.seasonVersion()||!character.getId().equals(proof.characterId())
                    ||selection.getVersion()!=proof.selectionVersion()||!Objects.equals(selection.getCompanionDeviceId(),proof.deviceId())
                    ||!"WOW_FOREVER".equals(proof.product())||!"EU".equals(proof.region())
                    ||proof.observedAt()==null||proof.observedAt().isAfter(now)||proof.observedAt().isBefore(now.minusSeconds(45))
                    ||proof.validUntil()==null||!proof.validUntil().isAfter(now)
                    ||proof.ruleset()==null||proof.ruleset().isBlank()||proof.groupingKey()==null||proof.groupingKey().isBlank()
                    ||proof.characterClass()==null||proof.characterClass().isBlank()))proof=null;
            UUID subject=season.getRatingSubjectType()==RatingSubjectType.ACCOUNT?account:character.getId();
            values=bot?new SoloQueueCompetitiveData.Snapshot(season.getId(),season.getRatingSubjectType(),subject,1500,0,0,0)
                    :ratings.get(subject);
            if(values!=null&&(!season.getId().equals(values.seasonId())||values.subjectType()!=season.getRatingSubjectType()
                    ||!subject.equals(values.subjectId())||values.mmr()<0||values.visibleRating()<0||values.gamesPlayed()<0||values.priorCaptainAssignments()<0))values=null;
        }
        if(!preview&&proof==null)blockers.addAll(List.of("FOREVER_COMPATIBILITY_UNVERIFIED","ADDON_STATUS_UNVERIFIED","GAME_PRESENCE_UNVERIFIED"));
        if(values==null)blockers.add("COMPETITIVE_DATA_UNAVAILABLE");
        if(!preview&&!game.handoffAvailable())blockers.add("MATCH_ROOM_UNAVAILABLE");
        SoloQueueCandidate candidate=blockers.isEmpty()?new SoloQueueCandidate(account,character.getId(),season.getId(),character.getRegion(),
                preview?PreseasonQueue.RULESET:proof.ruleset(),preview?PreseasonQueue.GROUPING_KEY:proof.groupingKey(),
                selection.getMatchRole(),selection.isCaptainConsent(),now,values.mmr(),preview?previewClass:proof.characterClass(),
                values.priorCaptainAssignments(),values.gamesPlayed(),values.visibleRating(),UUID.randomUUID()):null;
        return new Result(List.copyOf(blockers),candidate,season==null?null:season.getVersion(),selection==null?null:selection.getVersion(),
                bot||selection==null?null:selection.getCompanionDeviceId());
    }
}
