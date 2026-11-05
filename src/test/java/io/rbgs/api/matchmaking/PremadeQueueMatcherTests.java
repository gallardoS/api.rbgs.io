package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.MatchRole;
import io.rbgs.api.seasons.RatingSubjectType;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremadeQueueMatcherTests {
    private final Instant now=Instant.parse("2026-11-05T12:00:00Z");
    private final UUID season=UUID.randomUUID();
    private final PremadeQueueMatcher matcher=new PremadeQueueMatcher();

    private PremadeQueueCandidate group(int mmr,int wait,MatchRole role,String key) {
        var players=new ArrayList<SoloQueueCandidate>();
        for(int i=0;i<10;i++)players.add(new SoloQueueCandidate(UUID.randomUUID(),UUID.randomUUID(),season,"EU","fixture",key,
                role,false,now.minusSeconds(wait),mmr,"class",0));
        return new PremadeQueueCandidate(UUID.randomUUID(),0,players.getFirst().accountId(),RatingSubjectType.ACCOUNT,players);
    }
    @Test void unrestrictedCompositionsKeepDesignatedCaptainsAndAllParticipants() {
        var a=group(1500,100,MatchRole.DPS,"a");var b=group(1500,10,MatchRole.FC,"b");
        var result=matcher.match(List.of(b,a),now,(r,x,y)->true,w->0).orElseThrow();
        assertEquals(a,result.teamA());assertEquals(b,result.teamB());
        assertEquals(a.captainId(),result.sharedRoom().captainA());assertNull(result.sharedRoom().composition());
        assertEquals(a.members(),result.sharedRoom().teamA());
    }
    @Test void oldestFeasibleGroupGetsClosestMmrOpponentDeterministically() {
        var oldest=group(1500,100,MatchRole.DPS,"a");var far=group(1600,90,MatchRole.DPS,"a");
        var close=group(1501,10,MatchRole.DPS,"a");
        var result=matcher.match(List.of(close,far,oldest),now,(r,x,y)->true,w->200).orElseThrow();
        assertEquals(oldest,result.teamA());assertEquals(close,result.teamB());
    }
    @Test void wideningUsesOldestFeasibleGroupWaitAndExactBoundary() {
        var a=group(1500,120,MatchRole.DPS,"a");var b=group(1600,1,MatchRole.DPS,"a");
        assertTrue(matcher.match(List.of(a,b),now.minusSeconds(1),(r,x,y)->true,w->w.toSeconds()>=120?100:99).isEmpty());
        assertTrue(matcher.match(List.of(a,b),now,(r,x,y)->true,w->w.toSeconds()>=120?100:99).isPresent());
    }
    @Test void incompatibleOlderGroupCannotRelaxAnotherPair() {
        var older=group(1500,1000,MatchRole.DPS,"isolated");var a=group(1500,10,MatchRole.DPS,"a");var b=group(1600,1,MatchRole.DPS,"a");
        assertTrue(matcher.match(List.of(older,a,b),now,(r,x,y)->x.equals(y),w->w.toSeconds()>=120?100:99).isEmpty());
    }
    @Test void invalidRosterAndOverlappingAccountsAreRejected() {
        var a=group(1500,10,MatchRole.DPS,"a");
        assertThrows(IllegalArgumentException.class,()->new PremadeQueueCandidate(a.groupId(),0,a.captainId(),a.ratingSubjectType(),a.members().subList(0,9)));
        assertThrows(IllegalArgumentException.class,()->new PremadeQueueCandidate(a.groupId(),0,UUID.randomUUID(),a.ratingSubjectType(),a.members()));
        var duplicate=new PremadeQueueCandidate(UUID.randomUUID(),0,a.captainId(),a.ratingSubjectType(),a.members());
        assertThrows(IllegalArgumentException.class,()->matcher.match(List.of(a,duplicate),now,(r,x,y)->true,w->100));
    }
    @Test void hundredPremadePlayersProduceFiveDisjointMatches() {
        var waiting=new ArrayList<PremadeQueueCandidate>();for(int i=0;i<10;i++)waiting.add(group(1500,i,MatchRole.DPS,"a"));
        var used=new HashSet<UUID>();
        for(int i=0;i<5;i++) {
            var match=matcher.match(waiting,now,(r,x,y)->true,w->100).orElseThrow();
            for(var g:List.of(match.teamA(),match.teamB())) { g.members().forEach(c->assertTrue(used.add(c.accountId())));waiting.remove(g); }
        }
        assertEquals(100,used.size());assertTrue(waiting.isEmpty());
    }
    @Test void arithmeticMeanDoesNotOverflowAndInvalidWindowIsRejected() {
        var a=group(Integer.MAX_VALUE,10,MatchRole.DPS,"a");assertEquals(Integer.MAX_VALUE,a.meanMmr());
        assertThrows(IllegalArgumentException.class,()->matcher.match(List.of(a),now,(r,x,y)->true,w->Double.NaN));
    }
    @Test void regionSeasonRulesetAndRatingSubjectPartitionsCannotMix() {
        var a=group(1500,10,MatchRole.DPS,"a");var original=group(1500,1,MatchRole.DPS,"a");
        for(int variation=0;variation<4;variation++) {
            var altered=new ArrayList<SoloQueueCandidate>();
            for(var c:original.members())altered.add(new SoloQueueCandidate(c.accountId(),c.characterId(),variation==0?UUID.randomUUID():c.seasonId(),
                    variation==1?"NA":c.region(),variation==2?"other":c.ruleset(),c.groupingKey(),c.role(),false,c.enteredAt(),c.mmr(),c.characterClass(),0));
            // A changed season must still be identical within that premade.
            if(variation==0) {
                var differentSeason=UUID.randomUUID();altered.clear();
                for(var c:original.members())altered.add(new SoloQueueCandidate(c.accountId(),c.characterId(),differentSeason,c.region(),c.ruleset(),c.groupingKey(),c.role(),false,c.enteredAt(),c.mmr(),c.characterClass(),0));
            }
            var b=new PremadeQueueCandidate(original.groupId(),0,original.captainId(),variation==3?RatingSubjectType.CHARACTER:RatingSubjectType.ACCOUNT,altered);
            assertTrue(matcher.match(List.of(a,b),now,(r,x,y)->true,w->100).isEmpty());
        }
    }
}
