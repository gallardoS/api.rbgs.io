package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.MatchRole;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoloQueueMatcherTests {
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final UUID SEASON = new UUID(0, 1);
    private final SoloQueueMatcher matcher = new SoloQueueMatcher();

    private List<SoloQueueCandidate> roster(int fc, int healer, int dps, int wait) {
        var result = new ArrayList<SoloQueueCandidate>();
        for (int i = 0; i < 2 * (fc + healer + dps); i++) {
            var role = i < fc * 2 ? MatchRole.FC : i < (fc + healer) * 2 ? MatchRole.HEALER : MatchRole.DPS;
            result.add(new SoloQueueCandidate(new UUID(0, i + 1), new UUID(1, i + 1), SEASON, "EU", "verified-fixture",
                    "groupable", role, true, NOW.minusSeconds(wait), 1500 + i * 7, "class" + i % 4, i));
        }
        return result;
    }
    private Optional<SoloQueueMatcher.Proposal> match(List<SoloQueueCandidate> queue) {
        return matcher.match(queue, NOW, (a, b) -> true);
    }
    private SoloQueueCandidate change(SoloQueueCandidate c, String key, boolean captain, Instant entered, int rotation) {
        return new SoloQueueCandidate(c.accountId(), c.characterId(), c.seasonId(), c.region(), c.ruleset(), key,
                c.role(), captain, entered, c.mmr(), c.characterClass(), rotation,c.gamesPlayed(),c.visibleRating(),c.captainLottery());
    }
    private void valid(SoloQueueMatcher.Proposal p) {
        assertEquals(10, p.teamA().size()); assertEquals(10, p.teamB().size());
        var all = new ArrayList<>(p.teamA()); all.addAll(p.teamB());
        assertEquals(20, all.stream().map(SoloQueueCandidate::accountId).distinct().count());
        for (var role : MatchRole.values()) assertEquals(p.teamA().stream().filter(c -> c.role() == role).count(),
                p.teamB().stream().filter(c -> c.role() == role).count());
        assertTrue(p.teamA().stream().anyMatch(c -> c.role() == MatchRole.HEALER));
        for(var team:List.of(p.teamA(),p.teamB())) {
            UUID id=team==p.teamA()?p.captainA():p.captainB();
            assertTrue(team.stream().anyMatch(c->c.accountId().equals(id)));
            if(team.stream().anyMatch(SoloQueueCandidate::captainConsent))
                assertTrue(team.stream().anyMatch(c->c.accountId().equals(id)&&c.captainConsent()));
        }
    }
    @Test void allSixCompositionsAndExactRelaxationBoundaries() {
        for (int[] c : new int[][]{{1,3,6,0},{1,2,7,0},{0,3,7,120},{0,2,8,120},{1,1,8,240},{0,1,9,240}}) {
            var queue = roster(c[0], c[1], c[2], c[3]);
            var proposal = match(queue).orElseThrow(); valid(proposal);
            assertEquals(new SoloQueueMatcher.Composition(c[0], c[1], c[2]), proposal.composition());
            if (c[3] > 0) assertTrue(match(roster(c[0],c[1],c[2],c[3]-1)).isEmpty());
        }
    }
    @Test void neverForcesMissingRolesButAssignsCaptainsWhenNobodyVolunteers() {
        assertTrue(match(roster(1,0,9,1000)).isEmpty());
        var queue = roster(1,3,6,1000);
        valid(match(queue.stream().map(c -> change(c, c.groupingKey(), false, c.enteredAt(), 0)).toList()).orElseThrow());
        queue.removeFirst(); assertTrue(match(queue).isEmpty());
    }
    @Test void unknownGroupingAndOppositionStayWaiting() {
        var queue = roster(1,3,6,500);
        assertTrue(matcher.match(queue, NOW, (a,b) -> false).isEmpty());
        var unique = queue.stream().map(c -> change(c, c.accountId().toString(), true, c.enteredAt(),0)).toList();
        assertTrue(match(unique).isEmpty());
    }
    @Test void factionTeamsRemainGroupable() {
        var queue = roster(1,3,6,0);
        var split = new ArrayList<SoloQueueCandidate>();
        for (int i = 0; i < queue.size(); i++) split.add(change(queue.get(i), i % 2 == 0 ? "alliance" : "horde", true, queue.get(i).enteredAt(),0));
        var p = matcher.match(split, NOW, (a,b) -> !a.equals(b)).orElseThrow(); valid(p);
        assertEquals(1, p.teamA().stream().map(SoloQueueCandidate::groupingKey).distinct().count());
        assertEquals(1, p.teamB().stream().map(SoloQueueCandidate::groupingKey).distinct().count());
    }
    @Test void unrelatedOldEntrantDoesNotRelaxYoungerPool() {
        var queue = roster(0,1,9,239);
        var old = queue.getFirst();
        queue.add(new SoloQueueCandidate(new UUID(5,5), new UUID(6,6), new UUID(7,7), "EU", old.ruleset(),old.groupingKey(),
                MatchRole.DPS,true,NOW.minusSeconds(1000),1500,"class",0));
        assertTrue(match(queue).isEmpty());
    }
    @Test void restartAndInputOrderingAreDeterministicAndCaptainsRotate() {
        var queue = roster(1,3,6,0);
        var first = match(queue).orElseThrow();
        Collections.shuffle(queue, new Random(81));
        assertEquals(first, new SoloQueueMatcher().match(queue,NOW,(a,b)->true).orElseThrow());
        for (var team : List.of(first.teamA(), first.teamB())) {
            var expected = team.stream().min(Comparator.comparingInt(SoloQueueCandidate::priorCaptainAssignments).thenComparing(SoloQueueMatcher.ORDER)).orElseThrow();
            assertEquals(expected.accountId(), team == first.teamA() ? first.captainA() : first.captainB());
        }
    }
    @Test void hundredPlayersProduceFiveDisjointMatches() {
        var queue = new ArrayList<SoloQueueCandidate>();
        for (int batch = 0; batch < 5; batch++) for (var c : roster(1,3,6,0)) {
            queue.add(new SoloQueueCandidate(new UUID(batch,c.accountId().getLeastSignificantBits()),
                    new UUID(batch+10,c.characterId().getLeastSignificantBits()),SEASON,c.region(),c.ruleset(),c.groupingKey(),
                    c.role(),c.captainConsent(),c.enteredAt(),c.mmr(),c.characterClass(),0));
        }
        var seen = new HashSet<UUID>();
        for (int i = 0; i < 5; i++) {
            var p = match(queue).orElseThrow(); valid(p);
            for (var team : List.of(p.teamA(),p.teamB())) for (var c : team) assertTrue(seen.add(c.accountId()));
            queue.removeIf(c -> seen.contains(c.accountId()));
        }
        assertTrue(queue.isEmpty());
    }
    @Test void duplicateAccountsAreRejected() {
        var queue = roster(1,3,6,0); queue.add(queue.getFirst());
        assertThrows(IllegalArgumentException.class, () -> match(queue));
    }
    @Test void teamSplitMinimizesMmrDifferenceForTheOldestFeasibleRoster() {
        var p = match(roster(1,3,6,0)).orElseThrow();
        assertEquals(p.teamA().stream().mapToInt(SoloQueueCandidate::mmr).sum(),
                p.teamB().stream().mapToInt(SoloQueueCandidate::mmr).sum());
    }
    @Test void youngerVolunteersDoNotDisplaceOlderEntrantsNowThatFallbackExists() {
        var queue = new ArrayList<>(roster(1,3,6,300).stream()
                .map(c -> change(c,c.groupingKey(),false,c.enteredAt(),0)).toList());
        var example = queue.getLast();
        for (int i = 21; i <= 22; i++) queue.add(new SoloQueueCandidate(new UUID(0,i),new UUID(1,i),SEASON,
                example.region(),example.ruleset(),example.groupingKey(),MatchRole.DPS,true,NOW.minusSeconds(1),1500,"class",0));
        var proposal = match(queue).orElseThrow(); valid(proposal);
        var selected = new ArrayList<>(proposal.teamA()); selected.addAll(proposal.teamB());
        for (int i = 0; i < 20; i++) assertTrue(selected.contains(queue.get(i)));
        assertFalse(selected.contains(queue.get(20))); assertFalse(selected.contains(queue.get(21)));
    }
    private SoloQueueCandidate competitive(SoloQueueCandidate c,boolean volunteer,int games,int rating,int draw) {
        return new SoloQueueCandidate(c.accountId(),c.characterId(),c.seasonId(),c.region(),c.ruleset(),c.groupingKey(),c.role(),
                volunteer,c.enteredAt(),c.mmr(),c.characterClass(),c.priorCaptainAssignments(),games,rating,new UUID(0,draw));
    }
    @Test void fallbackUsesGamesThenVisibleRatingThenPersistedLottery() {
        var candidates=roster(1,3,6,0);
        var experienced=competitive(candidates.get(0),false,30,100,9);
        var highRating=competitive(candidates.get(1),false,20,5000,1);
        assertEquals(experienced,SoloQueueMatcher.captain(List.of(experienced,highRating)));
        var sameExperience=competitive(candidates.get(2),false,30,200,8);
        assertEquals(sameExperience,SoloQueueMatcher.captain(List.of(experienced,sameExperience)));
        var tied=competitive(candidates.get(3),false,30,200,2);
        assertEquals(tied,SoloQueueMatcher.captain(List.of(sameExperience,tied)));
    }
    @Test void allNewFallbackIsAReproducibleLotteryWithoutMmrOrSeniorityPreference() {
        var queue=roster(1,3,6,0);
        var entries=new ArrayList<SoloQueueCandidate>();
        for(int i=0;i<queue.size();i++) entries.add(competitive(queue.get(i),false,0,0,i==6?1:100+i));
        assertEquals(entries.get(6),SoloQueueMatcher.captain(entries));
        var first=match(entries).orElseThrow(); valid(first);
        Collections.reverse(entries); assertEquals(first,match(entries).orElseThrow());
        var otherDraw=entries.stream().map(c->competitive(c,false,0,0,c.accountId().equals(queue.get(12).accountId())?0:500)).toList();
        assertEquals(queue.get(12).accountId(),SoloQueueMatcher.captain(otherDraw).accountId());
    }
    @Test void willingPlayersWinOverAutomaticExperienceAndAreDistributedAcrossTeams() {
        var base=roster(1,3,6,0); var queue=new ArrayList<SoloQueueCandidate>();
        for(int i=0;i<base.size();i++) queue.add(competitive(base.get(i),i>=18,100-i,3000-i,i));
        var proposal=match(queue).orElseThrow();valid(proposal);
        assertEquals(Set.of(base.get(18).accountId(),base.get(19).accountId()),Set.of(proposal.captainA(),proposal.captainB()));
    }
    @Test void oneTeamWithoutVolunteersUsesFallbackWithoutBreakingGroupingRules() {
        var base=roster(1,3,6,0);var queue=new ArrayList<SoloQueueCandidate>();
        for(int i=0;i<base.size();i++)queue.add(change(base.get(i),i%2==0?"alliance":"horde",i==0||i==2,base.get(i).enteredAt(),0));
        var proposal=matcher.match(queue,NOW,(a,b)->!a.equals(b)).orElseThrow();valid(proposal);
        for(var team:List.of(proposal.teamA(),proposal.teamB()))assertEquals(1,team.stream().map(SoloQueueCandidate::groupingKey).distinct().count());
    }
}
