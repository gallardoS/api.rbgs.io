package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.devices.DeviceRepository;
import io.rbgs.api.groups.GroupMemberRepository;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.matches.MatchAssignmentRepository;
import io.rbgs.api.seasons.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class SoloQueueEligibility {
    private final SelectionSettings settings;
    private final SeasonRepository seasons;
    private final SeasonSelectionRepository selections;
    private final CharacterIdentityRepository characters;
    private final DeviceRepository devices;
    private final AccountRepository accounts;
    private final GroupMemberRepository groups;
    private final MatchAssignmentRepository assignments;
    private final SoloQueueGameGateway game;
    private final SoloQueueCompetitiveData competitive;
    public record Result(List<String> blockers,SoloQueueCandidate candidate,Long seasonVersion,Long selectionVersion,UUID deviceId) { }

    public Result inspect(UUID account,Instant now) {
        var blockers=new ArrayList<String>();
        if(accounts.findById(account).filter(a->a.getStatus().equals("ACTIVE")).isEmpty())blockers.add("ACCOUNT_UNAVAILABLE");
        if(groups.existsById(account))blockers.add("GROUP_MEMBERSHIP_EXISTS");
        if(assignments.hasBlockingParticipation(account,now))blockers.add("MATCH_UNRESOLVED");
        var season=settings.seasonId()==null?null:seasons.findById(settings.seasonId()).orElse(null);
        var selection=season==null?null:selections.findByAccountIdAndSeasonId(account,season.getId()).orElse(null);
        if(season==null)blockers.add("SEASON_UNAVAILABLE");
        else if(season.getStartedAt()==null||season.getStartedAt().isAfter(now))blockers.add("SEASON_NOT_STARTED");
        var character=selection==null?null:characters.findById(selection.getCharacterId()).orElse(null);
        if(selection==null||character==null)blockers.add("SELECTION_REQUIRED");
        else if(selection.getMatchRole()==null||!character.getAccountId().equals(account)||!character.getProduct().equals("WOW_FOREVER")||!character.getRegion().equals("EU")
                ||selection.getRatingSubjectType()!=season.getRatingSubjectType()
                ||season.getRatingSubjectType()==RatingSubjectType.CHARACTER&&character.getSource()!=CharacterIdentity.Source.BLIZZARD_VERIFIED
                ||season.getRatingSubjectType()==RatingSubjectType.ACCOUNT&&character.getSource()!=CharacterIdentity.Source.DECLARED)
            blockers.add("SELECTION_INVALID");
        var device=selection==null||selection.getCompanionDeviceId()==null?null:devices.findById(selection.getCompanionDeviceId()).orElse(null);
        boolean connected=device!=null&&device.getAccountId().equals(account)&&device.getRevokedAt()==null&&device.getRemovedAt()==null
                &&device.getExpiresAt().isAfter(now)&&device.getLastSeenAt()!=null&&!device.getLastSeenAt().isAfter(now)
                &&device.getLastSeenAt().isAfter(now.minusSeconds(45));
        if(!connected)blockers.add("COMPANION_DISCONNECTED");
        if(device==null||!Arrays.asList(device.getGrantedScope().split(" ")).contains("evidence:write"))blockers.add("CAPTURE_PERMISSION_REQUIRED");
        SoloQueueGameGateway.VerifiedPlayer proof=null;
        SoloQueueCompetitiveData.Snapshot values=null;
        if(season!=null&&selection!=null&&character!=null) {
            proof=game.inspect(account,season,selection).orElse(null);
            if(proof!=null && (!account.equals(proof.accountId())||!season.getId().equals(proof.seasonId())
                    ||season.getVersion()!=proof.seasonVersion()||!character.getId().equals(proof.characterId())
                    ||selection.getVersion()!=proof.selectionVersion()||!Objects.equals(selection.getCompanionDeviceId(),proof.deviceId())
                    ||!"WOW_FOREVER".equals(proof.product())||!"EU".equals(proof.region())
                    ||proof.observedAt()==null||proof.observedAt().isAfter(now)||proof.observedAt().isBefore(now.minusSeconds(45))
                    ||proof.validUntil()==null||!proof.validUntil().isAfter(now)
                    ||proof.ruleset()==null||proof.ruleset().isBlank()||proof.groupingKey()==null||proof.groupingKey().isBlank()
                    ||proof.characterClass()==null||proof.characterClass().isBlank()))proof=null;
            UUID subject=season.getRatingSubjectType()==RatingSubjectType.ACCOUNT?account:character.getId();
            values=competitive.confirmed(season.getId(),season.getRatingSubjectType(),subject).orElse(null);
            if(values!=null&&(!season.getId().equals(values.seasonId())||values.subjectType()!=season.getRatingSubjectType()
                    ||!subject.equals(values.subjectId())||values.mmr()<0||values.visibleRating()<0||values.gamesPlayed()<0||values.priorCaptainAssignments()<0))values=null;
        }
        if(proof==null)blockers.addAll(List.of("FOREVER_COMPATIBILITY_UNVERIFIED","ADDON_STATUS_UNVERIFIED","GAME_PRESENCE_UNVERIFIED"));
        if(values==null)blockers.add("COMPETITIVE_DATA_UNAVAILABLE");
        if(!game.handoffAvailable())blockers.add("MATCH_ROOM_UNAVAILABLE");
        SoloQueueCandidate candidate=blockers.isEmpty()?new SoloQueueCandidate(account,character.getId(),season.getId(),proof.region(),proof.ruleset(),
                proof.groupingKey(),selection.getMatchRole(),selection.isCaptainConsent(),now,values.mmr(),proof.characterClass(),
                values.priorCaptainAssignments(),values.gamesPlayed(),values.visibleRating(),UUID.randomUUID()):null;
        return new Result(List.copyOf(blockers),candidate,season==null?null:season.getVersion(),selection==null?null:selection.getVersion(),
                selection==null?null:selection.getCompanionDeviceId());
    }
}
