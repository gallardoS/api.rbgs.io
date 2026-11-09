package io.rbgs.api.matchmaking;

import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.characters.selection.CharacterIdentityRepository;
import io.rbgs.api.characters.selection.MatchRole;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import io.rbgs.api.characters.selection.SelectionSettings;
import io.rbgs.api.characters.PlayCharacterService;
import io.rbgs.api.groups.GroupMember;
import io.rbgs.api.groups.GroupMemberRepository;
import io.rbgs.api.groups.PremadeGroup;
import io.rbgs.api.groups.PremadeGroupRepository;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.ratings.LeaderboardService;
import io.rbgs.api.seasons.RatingSubjectType;
import io.rbgs.api.seasons.SeasonRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "rbgs.play.preseason-preview=false")
@Transactional
class PreseasonQueueIntegrationTests {
    @Autowired JdbcTemplate db;
    @Autowired AccountService accounts;
    @Autowired CharacterIdentityRepository characters;
    @Autowired SeasonSelectionRepository selections;
    @Autowired SeasonRepository seasons;
    @Autowired ConfirmedRatingStore ratings;
    @Autowired SoloQueueService solo;
    @Autowired SoloQueueStore soloStore;
    @Autowired PremadeQueueService premade;
    @Autowired PremadeQueueStore premadeStore;
    @Autowired PremadeGroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired SoloQueueRooms rooms;
    @Autowired QueueTicketRepository tickets;
    @Autowired LeaderboardService leaderboard;
    @Autowired SoloQueueGameGateway game;
    @Autowired PlayCharacterService playCharacters;
    @MockitoBean SelectionSettings settings;
    @MockitoSpyBean PreseasonQueue preseason;

    @Test
    void publicPreseasonCanRehearseBothQueuesWithoutCreatingAMatchOrChangingRating() {
        assertFalse(seasons.existsById(PreseasonQueue.SEASON_ID), "Run against a database without a started Preseason");
        when(settings.seasonId()).thenReturn(PreseasonQueue.SEASON_ID);
        var preview = new PreseasonQueue(true, settings, db, playCharacters);
        preview.run(null);
        var season = seasons.findById(PreseasonQueue.SEASON_ID).orElseThrow();
        doReturn(preview.active(season)).when(preseason).active(argThat(value -> value != null && season.getId().equals(value.getId())));
        assertEquals("Preseason", season.getName());
        assertFalse(game.handoffAvailable());
        assertTrue(leaderboard.page(ConfirmedRatingStore.Queue.SOLO, null, null, List.of(), "", 1, 0, true).seasons()
                .stream().anyMatch(item -> item.id().equals(season.getId())));
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID account = accounts.upsert("https://preseason-queue-test.example", UUID.randomUUID().toString(), "Player" + i).id();
            players.add(account);
            String name = "PreseasonPlayer" + i;
            var character = characters.saveAndFlush(CharacterIdentity.declared(account, name, "Test Realm"));
            UUID device = UUID.randomUUID();
            db.update("""
                    INSERT INTO rbgs.devices(id, account_id, token_hash, created_at, expires_at, granted_scope, last_seen_at)
                    VALUES (?, ?, ?, now(), now() + interval '1 day', 'companion:status companion:assignments evidence:write', now())
                    """, device, account, UUID.randomUUID().toString());
            db.update("""
                    INSERT INTO rbgs.play_characters(account_id, namespace, wow_account_id, realm_id, character_id, region, name, realm)
                    VALUES (?, 'profile-forever-eu', 1, 1, ?, 'EU', ?, 'Test Realm')
                    """, account, i + 1, name);
            var selection = new SeasonSelection(account, season.getId());
            selection.choose(character.getId(), RatingSubjectType.ACCOUNT,
                    i < 2 ? MatchRole.FC : i < 8 ? MatchRole.HEALER : MatchRole.DPS, false);
            selection.useDevice(device);
            selection = selections.saveAndFlush(selection);
            ratings.initializeSelection(selection.getId());
            var admission = new SoloQueueService.Admission(season.getId(), season.getVersion(), selection.getVersion());
            if (i == 0) {
                db.update("UPDATE rbgs.play_characters SET namespace='profile-classicann-eu' WHERE account_id=?", account);
                assertFalse(solo.enter(account, admission));
                db.update("UPDATE rbgs.play_characters SET namespace='profile-forever-eu' WHERE account_id=?", account);
            }
            assertTrue(solo.enter(account, admission), () -> solo.status(account).blockers().toString());
        }
        UUID soloRoom = soloStore.proposeReal().orElseThrow().getId();
        assertTrue(solo.status(players.getFirst()).room().preseasonPreview());
        for (var player : players) solo.respond(player, soloRoom, true);
        var soloStatus = solo.status(players.getFirst());
        assertEquals("PREPARING", soloStatus.room().status());
        assertNull(soloStatus.matchId());
        rooms.expireReal(soloStatus.room().preparationDeadline());
        assertEquals(PreseasonQueue.COMPLETED, solo.status(players.getFirst()).room().cancellationReason());
        assertTrue(players.stream().noneMatch(tickets::hasReservation));

        for (int i = 0; i < 20; i += 10) {
            UUID captain = players.get(i);
            var roster = players.subList(i, i + 10);
            var group = groups.saveAndFlush(new PremadeGroup(captain, "EU"));
            for (var player : roster) members.saveAndFlush(new GroupMember(player, group.getId()));
            assertTrue(premade.enter(captain, new PremadeQueueService.Admission(group.getId(), group.getRevision())),
                    () -> premade.status(captain).blockers().toString());
        }
        UUID premadeRoom = premadeStore.proposeReal().orElseThrow().getId();
        for (var player : players) premade.respond(player, premadeRoom, true);
        var premadeStatus = premade.status(players.getFirst());
        assertEquals("PREPARING", premadeStatus.room().status());
        assertTrue(premadeStatus.room().preseasonPreview());
        assertNull(premadeStatus.matchId());
        rooms.expireReal(premadeStatus.room().preparationDeadline());
        assertEquals(PreseasonQueue.COMPLETED, premade.status(players.getFirst()).room().cancellationReason());
        assertTrue(players.stream().noneMatch(tickets::hasReservation));
        for (var player : players) for (var queue : ConfirmedRatingStore.Queue.values()) {
            var rating = ratings.confirmed(season.getId(), queue, RatingSubjectType.ACCOUNT, player).orElseThrow();
            assertEquals(0, rating.gamesPlayed());
            assertEquals(0, rating.visibleRating());
        }
    }
}
