package io.rbgs.api.groups;

import io.rbgs.api.characters.selection.*;
import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.identity.*;
import io.rbgs.api.seasons.*;
import io.rbgs.api.matches.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GroupIntegrationTests {
    @Autowired GroupService service;
    @Autowired io.rbgs.api.characters.PlayCharacterService playCharacters;
    @MockitoBean io.rbgs.api.characters.WowCharacterService provider;
    @Autowired GroupEventStreams streams;
    @Autowired org.springframework.session.SessionRepository sessions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final List<String> streamSessions = new ArrayList<>();
    @Autowired AccountService accounts;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired SeasonRepository seasons;
    @Autowired SelectionService selections;
    @Autowired DeviceService devices;
    @Autowired MatchRepository matches;
    @Autowired MatchAssignmentRepository assignments;
    @MockitoBean SelectionSettings settings;
    private final List<Account> players = new ArrayList<>();
    private final List<String> subjects = new ArrayList<>();
    private SeasonEntity season;
    private final List<UUID> fixtureMatches = new ArrayList<>();
    private static final String ISSUER = "https://group-fixture.example";

    @BeforeEach void setup() {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 12; i++) {
            subjects.add(UUID.randomUUID().toString());
            players.add(accounts.upsert(ISSUER, subjects.get(i), "Group" + prefix + "#" + (1000 + i)));
            db.update("INSERT INTO rbgs.play_characters(account_id,namespace,wow_account_id,realm_id,character_id,region,name,realm) VALUES (?, 'profile-classicann-eu',1,1,1,'EU','Character','Fixture realm')", players.get(i).id());
        }
        season = seasons.saveAndFlush(new SeasonEntity("Group fixture", SeasonKind.BETA, RatingSubjectType.ACCOUNT));
        when(settings.seasonId()).thenReturn(season.getId());
    }
    @AfterEach void cleanup() {
        for (var player : players) streams.closeAccount(player.id());
        for (var id : streamSessions) db.update("DELETE FROM rbgs.spring_session WHERE session_id=?", id);
        for (var id : fixtureMatches) {
            db.update("DELETE FROM rbgs.match_assignments WHERE match_id=?", id);
            db.update("DELETE FROM rbgs.matches WHERE id=?", id);
        }
        for (var p : players) db.update("DELETE FROM rbgs.premade_groups WHERE captain_id = ?", p.id());
        for (var p : players) {
            db.update("DELETE FROM rbgs.group_invitations WHERE account_id = ?", p.id());
            db.update("DELETE FROM rbgs.season_selections WHERE account_id = ?", p.id());
            db.update("DELETE FROM rbgs.character_identities WHERE account_id = ?", p.id());
            db.update("DELETE FROM rbgs.device_pairings WHERE account_id = ?", p.id());
            db.update("DELETE FROM rbgs.devices WHERE account_id = ?", p.id());
            db.update("DELETE FROM rbgs.accounts WHERE id = ?", p.id());
        }
        seasons.deleteById(season.getId());
    }
    private OAuth2AuthenticationToken auth(int i) {
        var token = new OidcIdToken("fixture", Instant.now(), Instant.now().plusSeconds(300), Map.of("iss", ISSUER, "sub", subjects.get(i)));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(() -> "ROLE_USER"), token), List.of(() -> "ROLE_USER"), "battle-net");
    }
    private RequestPostProcessor login(int i) { return oidcLogin().idToken(t -> t.issuer(ISSUER).subject(subjects.get(i))); }
    private GroupService.Group invite(GroupService.Group group, int captain, int target) {
        return service.invite(auth(captain), group.id(), group.revision(), players.get(target).displayName());
    }
    private GroupService.Group join(GroupService.Group group, int captain, int target) {
        invite(group, captain, target);
        return service.accept(auth(target), service.incoming(auth(target)).getFirst().id());
    }
    private SelectionView select(int player) {
        var pairing = devices.start(); devices.approve(auth(player), pairing.code());
        var device = devices.exchange(pairing.pollingSecret());
        devices.heartbeat("Bearer " + device.token());
        return selections.save(auth(player), new SelectionRequest(season.getId(), season.getVersion(), null, null,
                new SelectionRequest.Declaration("Character", "Fixture realm"), MatchRole.HEALER, true, device.deviceId()));
    }
    private void conflict(Runnable action) { assertEquals(409, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value()); }

    @SuppressWarnings("unchecked")
    private jakarta.servlet.http.Cookie streamCookie(int player) {
        var session = sessions.createSession();
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new org.springframework.security.core.context.SecurityContextImpl(auth(player)));
        sessions.save(session); streamSessions.add(session.getId());
        // MOCK web environment uses Spring Session's default cookie name.
        return new jakarta.servlet.http.Cookie("SESSION", session.getId());
    }
    private org.springframework.test.web.servlet.MvcResult stream(int player) throws Exception {
        var result = mvc.perform(get("/api/v1/me/group-events").cookie(streamCookie(player)))
            .andExpect(status().isOk()).andExpect(request().asyncStarted())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Accel-Buffering", "no")).andReturn();
        await(() -> result.getResponse().getContentAsString().contains("event:ready"));
        return result;
    }
    private void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.call(), "Expected asynchronous lobby event");
    }
    private int changes(org.springframework.test.web.servlet.MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString().split("event:changed", -1).length - 1;
    }
    @Test void sseOnlyInvalidatesRelevantAccountsAfterCommitAndNeverExposesLobbyData() throws Exception {
        var group = join(service.create(auth(0)), 0, 1);
        var captain = stream(0); var member = stream(1); var outsider = stream(2);
        Thread.sleep(100);
        int beforeCaptain = changes(captain), beforeMember = changes(member), beforeOutsider = changes(outsider);
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            service.displayRole(auth(1), group.id(), 0, MatchRole.HEALER);
            status.setRollbackOnly(); return null;
        });
        Thread.sleep(150);
        assertEquals(beforeCaptain, changes(captain)); assertEquals(beforeMember, changes(member));
        service.displayRole(auth(1), group.id(), 0, MatchRole.FC);
        await(() -> changes(captain) > beforeCaptain && changes(member) > beforeMember);
        assertEquals(beforeOutsider, changes(outsider));
        assertFalse(captain.getResponse().getContentAsString().contains(group.lobbyCode()));
        assertFalse(captain.getResponse().getContentAsString().contains(players.get(1).displayName()));
    }
    @Test void sseInvitationsReachUngroupedRecipientsAndCancellationRefreshesThem() throws Exception {
        var group = service.create(auth(0));
        var recipient = stream(1); var captain = stream(0);
        Thread.sleep(100); int beforeRecipient = changes(recipient), beforeCaptain = changes(captain);
        invite(group, 0, 1);
        await(() -> changes(recipient) > beforeRecipient && changes(captain) > beforeCaptain);
        int invited = changes(recipient);
        var invitation = service.incoming(auth(1)).getFirst();
        service.cancelInvitation(auth(0), group.id(), group.revision(), invitation.id());
        await(() -> changes(recipient) > invited);
        assertNull(service.own(auth(1))); assertTrue(service.incoming(auth(1)).isEmpty());
    }
    @Test void sseCodeSwitchNotifiesMembersOfBothSourceAndDestination() throws Exception {
        var source = join(service.create(auth(0)), 0, 2);
        var target = service.create(auth(1));
        var oldPeer = stream(2); var newPeer = stream(1); var caller = stream(0);
        Thread.sleep(100); int old = changes(oldPeer), next = changes(newPeer), self = changes(caller);
        service.join(auth(0), target.lobbyCode());
        await(() -> changes(oldPeer) > old && changes(newPeer) > next && changes(caller) > self);
        assertEquals(players.get(2).id(), service.own(auth(2)).captainId());
        assertEquals(target.id(), service.own(auth(0)).id());
        assertEquals(source.id(), service.own(auth(2)).id());
    }
    @Test void sseRejectsAnonymousAndSuspendedAccounts() throws Exception {
        mvc.perform(get("/api/v1/me/group-events")).andExpect(status().isUnauthorized());
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?", players.get(1).id());
        mvc.perform(get("/api/v1/me/group-events").cookie(streamCookie(1))).andExpect(status().isForbidden());
        assertEquals(0, streams.activeConnections());
    }
    @Test void sseBoundsConnectionsPerAccountAndReleasesSlots() throws Exception {
        var connections = new ArrayList<org.springframework.test.web.servlet.MvcResult>();
        for (int i = 0; i < 4; i++) connections.add(stream(0));
        streams.heartbeat();
        for (var connection : connections) await(() -> connection.getResponse().getContentAsString().contains("event:ping"));
        assertEquals(4, streams.activeConnections());
        // Use the same persisted browser-session path as the four accepted connections.
        mvc.perform(get("/api/v1/me/group-events").cookie(streamCookie(0))).andExpect(status().isTooManyRequests());
        assertEquals(4, streams.activeConnections());
        stream(1);
        assertEquals(5, streams.activeConnections());
        streams.closeAccount(players.get(0).id());
        assertEquals(1, streams.activeConnections());
        stream(0);
        assertEquals(2, streams.activeConnections());
        streams.closeAccount(players.get(0).id());
        streams.closeAccount(players.get(1).id());
        assertEquals(0, streams.activeConnections());
    }
    @Test void sseHeartbeatClosesInvalidatedSessionsAndSuspendedAccounts() throws Exception {
        var first = stream(0); var second = stream(1);
        db.update("DELETE FROM rbgs.spring_session WHERE session_id=?", streamSessions.getFirst());
        streams.heartbeat(); await(() -> streams.activeConnections() == 1);
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?", players.get(1).id());
        streams.heartbeat(); await(() -> streams.activeConnections() == 0);
        mvc.perform(asyncDispatch(second)).andExpect(status().isOk());
    }

    private void region(int player, String region) {
        db.update("UPDATE rbgs.play_characters SET namespace=?,region=? WHERE account_id=?",
                region.equals("NA") ? "profile-classicann-us" : "profile-classicann-eu", region, players.get(player).id());
    }
    @Test void lobbyRegionUsesSelectedCharacterAndSurvivesCaptainSuccession() {
        region(0,"NA"); region(1,"NA"); region(2,"NA");
        var group = service.create(auth(0));
        assertEquals("EU", players.get(0).region()); assertEquals("NA", group.region());
        group = join(group,0,1); group = join(group,0,2);
        group = service.transfer(auth(0),group.id(),group.revision(),players.get(1).id());
        assertEquals("NA", group.region());
        service.remove(auth(1),group.id(),group.revision(),players.get(1).id());
        assertEquals("NA", service.own(auth(0)).region());
        assertEquals(players.get(0).id(), service.own(auth(0)).captainId());
    }
    @Test void regionMismatchRejectsInvitesAndAtomicCodeSwitchesWithoutSourceChanges() {
        var source = service.create(auth(0));
        region(1,"NA"); var destination = service.create(auth(1));
        conflict(() -> invite(source,0,1));
        conflict(() -> service.join(auth(0),destination.lobbyCode()));
        assertEquals(source.id(), service.own(auth(0)).id());
        assertEquals(source.revision(),service.own(auth(0)).revision());
        assertEquals(1,service.own(auth(1)).members().size());
        region(2,"NA"); conflict(() -> service.join(auth(2),source.lobbyCode()));
        assertNull(service.own(auth(2)));
        region(2,"EU"); assertEquals(2,service.join(auth(2),source.lobbyCode()).members().size());
    }
    @Test void acceptingAnInvitationRechecksRegionWithoutConsumingItOnFailure() {
        var group=service.create(auth(0)); invite(group,0,1);
        var invitation=service.incoming(auth(1)).getFirst();
        region(1,"NA"); conflict(() -> service.accept(auth(1),invitation.id()));
        assertNull(service.own(auth(1))); assertEquals(1,service.incoming(auth(1)).size());
        region(1,"EU"); assertEquals("EU",service.accept(auth(1),invitation.id()).region());
    }
    private io.rbgs.api.characters.dto.WowAccountProfile ownedRegions() {
        var eu = new io.rbgs.api.characters.dto.WowAccountProfile.WowCharacter(70,"Owned EU",
                new io.rbgs.api.characters.dto.WowAccountProfile.Realm(1,"EU realm","eu-realm"),null,null,null,60,null,null,"profile-classicann-eu",null,null);
        var us = new io.rbgs.api.characters.dto.WowAccountProfile.WowCharacter(70,"Owned NA",
                new io.rbgs.api.characters.dto.WowAccountProfile.Realm(1,"NA realm","na-realm"),null,null,null,70,null,null,"profile-classicann-us",null,null);
        return new io.rbgs.api.characters.dto.WowAccountProfile(List.of(new io.rbgs.api.characters.dto.WowAccountProfile.WowAccount(7,List.of(eu,us))));
    }
    @Test void playCharacterVerifiesOwnershipWithoutSeasonOrDeviceAndLocksRegionWhileGrouped() {
        when(provider.currentCharacters(any())).thenReturn(ownedRegions()); when(settings.seasonId()).thenReturn(null);
        var na = new io.rbgs.api.characters.PlayCharacterService.Reference("profile-classicann-us",7,1,70);
        var eu = new io.rbgs.api.characters.PlayCharacterService.Reference("profile-classicann-eu",7,1,70);
        assertEquals("NA",playCharacters.save(auth(0),null,na).region());
        assertEquals("Owned NA",playCharacters.find(players.get(0).id()).name());
        var group=service.create(auth(0));
        conflict(() -> playCharacters.save(auth(0),null,eu));
        assertEquals("NA",playCharacters.find(players.get(0).id()).region());
        assertEquals("NA",service.own(auth(0)).region());
        var bad=new io.rbgs.api.characters.PlayCharacterService.Reference("profile-classicann-us",999,1,70);
        assertEquals(422,assertThrows(ResponseStatusException.class,()->playCharacters.save(auth(0),null,bad)).getStatusCode().value());
        service.remove(auth(0),group.id(),group.revision(),players.get(0).id());
        assertEquals("EU",playCharacters.save(auth(0),null,eu).region());
    }
    @Test void playCharacterAndRegionEndpointsRequireAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/v1/me/play-character")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/play-character").with(login(0))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.region").value("EU"));
        mvc.perform(put("/api/v1/me/play-character").with(login(0)).contentType("application/json")
            .content("{\"namespace\":\"profile-classicann-us\",\"wowAccountId\":7,\"realmId\":1,\"characterId\":70}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/group").with(login(0))).andExpect(status().isOk());
        db.update("DELETE FROM rbgs.play_characters WHERE account_id=?",players.get(0).id());
        conflict(()->service.create(auth(0)));
    }
    @Test void playCharacterHttpResolvesOwnedReferenceAndNeverAcceptsArbitraryIds() throws Exception {
        when(provider.currentCharacters(any())).thenReturn(ownedRegions());
        var reference="{\"namespace\":\"profile-classicann-us\",\"wowAccountId\":7,\"realmId\":1,\"characterId\":70}";
        mvc.perform(put("/api/v1/me/play-character").with(login(0)).with(oauth2Client("battle-net").principalName(subjects.get(0)))
            .with(csrf()).contentType("application/json").content(reference)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.region").value("NA"))
            .andExpect(jsonPath("$.name").value("Owned NA"));
        mvc.perform(get("/api/v1/me/play-character").with(login(0))).andExpect(jsonPath("$.region").value("NA"));
        mvc.perform(put("/api/v1/me/play-character").with(login(0)).with(oauth2Client("battle-net").principalName(subjects.get(0)))
            .with(csrf()).contentType("application/json").content(reference.replace("70","999"))).andExpect(status().isUnprocessableContent());
        assertEquals("NA",playCharacters.find(players.get(0).id()).region());
        mvc.perform(post("/api/v1/groups").with(login(0)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.region").value("NA"));
    }
    @Test void verifiedCharacterChangeNotifiesPeersThroughSse() throws Exception {
        var group=join(service.create(auth(0)),0,1);
        when(provider.currentCharacters(any())).thenReturn(ownedRegions());
        var peer=stream(1); int count=changes(peer);
        playCharacters.save(auth(0),null,new io.rbgs.api.characters.PlayCharacterService.Reference("profile-classicann-eu",7,1,70));
        await(()->changes(peer)>count);
        assertEquals("Owned EU",service.own(auth(1)).members().stream().filter(m->m.accountId().equals(players.get(0).id())).findFirst().orElseThrow().characterName());
        assertEquals(group.region(),service.own(auth(1)).region());
    }
    private io.rbgs.api.characters.dto.WowAccountProfile profileCatalog(String name, int level, String avatar) {
        var character = new io.rbgs.api.characters.dto.WowAccountProfile.WowCharacter(70, name,
            new io.rbgs.api.characters.dto.WowAccountProfile.Realm(1,"EU realm","eu-realm"),
            new io.rbgs.api.characters.dto.WowAccountProfile.NamedReference(11L,"Druid",null),
            new io.rbgs.api.characters.dto.WowAccountProfile.NamedReference(4L,"Night Elf",null),
            new io.rbgs.api.characters.dto.WowAccountProfile.NamedReference(0L,"Alliance",null),
            level, avatar, null, "profile-classicann-eu",
            new io.rbgs.api.characters.dto.WowAccountProfile.NamedReference(99L,"Fixture Guild",null),
            new io.rbgs.api.characters.dto.WowAccountProfile.NamedReference(null,"Female","FEMALE"));
        return new io.rbgs.api.characters.dto.WowAccountProfile(List.of(
            new io.rbgs.api.characters.dto.WowAccountProfile.WowAccount(7,List.of(character))));
    }
    private io.rbgs.api.characters.PlayCharacterService.Reference profileReference() {
        return new io.rbgs.api.characters.PlayCharacterService.Reference("profile-classicann-eu",7,1,70);
    }
    @Test void selectedProfileIsSharedOnlyWithLobbyMembersWithoutProviderFanout() throws Exception {
        when(provider.currentCharacters(any())).thenReturn(profileCatalog("Owned Druid",70,
            "https://render.worldofwarcraft.com/classicann-eu/character/eu-realm/1/70-avatar.jpg"));
        playCharacters.save(auth(0),null,profileReference());
        var group=join(service.create(auth(0)),0,1);
        clearInvocations(provider);
        mvc.perform(get("/api/v1/me/group").with(login(1))).andExpect(status().isOk())
            .andExpect(jsonPath("$.members[?(@.captain == true)].character.name").value(org.hamcrest.Matchers.contains("Owned Druid")))
            .andExpect(jsonPath("$.members[?(@.captain == true)].character.level").value(org.hamcrest.Matchers.contains(70)))
            .andExpect(jsonPath("$.members[?(@.captain == true)].character.playable_class.name").value(org.hamcrest.Matchers.contains("Druid")))
            .andExpect(jsonPath("$.members[?(@.captain == true)].character.guild.name").value(org.hamcrest.Matchers.contains("Fixture Guild")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wowAccountId"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wow_accounts"))));
        mvc.perform(get("/api/v1/me/group").with(login(2))).andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/me/group")).andExpect(status().isUnauthorized());
        assertNull(group.members().stream().filter(m->!m.captain()).findFirst().orElseThrow().character());
        assertEquals("https://render.worldofwarcraft.com/classicann-eu/character/eu-realm/1/70-avatar.jpg",
            service.own(auth(1)).members().stream().filter(GroupService.Member::captain).findFirst().orElseThrow().character().avatarUrl());
        verifyNoInteractions(provider);
    }
    @Test void profileRefreshKeepsSelectionAndRoleAndOnlyNotifiesForChangedData() throws Exception {
        when(provider.currentCharacters(any())).thenReturn(profileCatalog("Owned Druid",70,null));
        playCharacters.save(auth(0),null,profileReference());
        var group=join(service.create(auth(0)),0,1);
        group=service.displayRole(auth(0),group.id(),0,MatchRole.HEALER);
        var peer=stream(1);
        // Byte-identical JSONB after verification must not write or generate notifications.
        var before=db.queryForObject("SELECT xmin::text FROM rbgs.play_characters WHERE account_id=?",String.class,players.get(0).id());
        playCharacters.save(auth(0),null,profileReference());
        assertEquals(before,db.queryForObject("SELECT xmin::text FROM rbgs.play_characters WHERE account_id=?",String.class,players.get(0).id()));
        int count=changes(peer);
        when(provider.currentCharacters(any())).thenReturn(profileCatalog("Owned Druid",71,null));
        playCharacters.save(auth(0),null,profileReference());
        await(()->changes(peer)>count);
        var updated=service.own(auth(1));
        var captain=updated.members().stream().filter(GroupService.Member::captain).findFirst().orElseThrow();
        assertEquals(71,captain.character().level()); assertNull(captain.character().avatarUrl());
        assertEquals(MatchRole.HEALER,captain.role()); assertEquals(group.revision(),updated.revision());
        assertEquals(group.lobbyCode(),updated.lobbyCode());
        when(provider.currentCharacters(any())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(ResponseStatusException.class,()->playCharacters.save(auth(0),null,profileReference()));
        assertEquals(71,playCharacters.find(players.get(0).id()).character().level());
    }
    @Test void twoAccountHttpLifecycleSynchronizesInvitesRolesProfilesAndCaptainSuccession() throws Exception {
        when(provider.currentCharacters(any())).thenReturn(profileCatalog("Captain Druid",70,null));
        playCharacters.save(auth(0),null,profileReference());
        var first=stream(0); var second=stream(1);
        mvc.perform(post("/api/v1/groups").with(login(0)).with(csrf())).andExpect(status().isOk());
        var group=service.own(auth(0)); int peerEvents=changes(second);
        mvc.perform(post("/api/v1/groups/"+group.id()+"/invitations").with(login(0)).with(csrf()).contentType("application/json")
            .content("{\"expectedRevision\":"+group.revision()+",\"battleTag\":\""+players.get(1).displayName()+"\"}"))
            .andExpect(status().isOk());
        await(()->changes(second)>peerEvents);
        var invitation=service.incoming(auth(1)).getFirst();
        mvc.perform(post("/api/v1/me/group-invitations/"+invitation.id()+"/accept").with(login(2)).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/group-invitations/"+invitation.id()+"/accept").with(login(1)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(2));
        int leaderEvents=changes(first);
        mvc.perform(post("/api/v1/groups/"+group.id()+"/role").with(login(1)).with(csrf()).contentType("application/json")
            .content("{\"expectedRoleVersion\":0,\"role\":\"HEALER\"}"))
            .andExpect(status().isOk());
        await(()->changes(first)>leaderEvents);
        var current=service.own(auth(0));
        mvc.perform(post("/api/v1/groups/"+group.id()+"/members/remove").with(login(0)).with(csrf()).contentType("application/json")
            .content("{\"expectedRevision\":"+current.revision()+",\"accountId\":\""+players.get(0).id()+"\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/group").with(login(1))).andExpect(status().isOk())
            .andExpect(jsonPath("$.captainId").value(players.get(1).id().toString()))
            .andExpect(jsonPath("$.lobbyCode").value(group.lobbyCode()));
        mvc.perform(post("/api/v1/groups/join").with(login(0)).with(csrf()).contentType("application/json")
            .content("{\"code\":\""+group.lobbyCode()+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(2));
        current=service.own(auth(1));
        assertFalse(current.queueReady());
        assertTrue(current.members().stream().allMatch(m->"UNVERIFIED".equals(m.addonStatus())&&"UNKNOWN".equals(m.gamePresence())));
    }
    @Test void concurrentRegionSelectionAndCodeJoinCannotCreateMixedMembership() throws Exception {
        var group=service.create(auth(0));
        when(provider.currentCharacters(any())).thenReturn(ownedRegions());
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var change=executor.submit(()->{ start.await(); try { playCharacters.save(auth(1),null,new io.rbgs.api.characters.PlayCharacterService.Reference("profile-classicann-us",7,1,70)); } catch(ResponseStatusException e) { assertEquals(409,e.getStatusCode().value()); } return null; });
            var joining=executor.submit(()->{ start.await(); try { service.join(auth(1),group.lobbyCode()); } catch(ResponseStatusException e) { assertEquals(409,e.getStatusCode().value()); } return null; });
            start.countDown(); change.get(5,TimeUnit.SECONDS); joining.get(5,TimeUnit.SECONDS);
            var membership=service.own(auth(1));
            if(membership!=null) assertEquals(membership.region(),playCharacters.find(players.get(1).id()).region());
        }
    }

    @Test void httpRequiresAuthenticationCsrfAndCurrentActiveAccount() throws Exception {
        mvc.perform(get("/api/v1/me/group")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/groups").with(login(0))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/group").with(login(0))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/groups").with(login(0)).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1)).andExpect(jsonPath("$.queueReady").value(false));
        db.update("UPDATE rbgs.accounts SET account_status='SUSPENDED' WHERE id=?", players.get(0).id());
        mvc.perform(get("/api/v1/me/group").with(login(0))).andExpect(status().isForbidden());
    }
    @Test void invitationIsBoundToItsRecipientAndCannotBeEnumerated() throws Exception {
        var group = service.create(auth(0));
        invite(group, 0, 1);
        var invite = service.incoming(auth(1)).getFirst();
        mvc.perform(post("/api/v1/me/group-invitations/" + invite.id() + "/accept").with(login(2)).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        assertTrue(service.incoming(auth(2)).isEmpty());
        assertNull(service.own(auth(2)));
        assertEquals(2, service.accept(auth(1), invite.id()).members().size());
        assertTrue(service.incoming(auth(1)).isEmpty());
    }
    @Test void onlyCaptainCanInviteRemoveTransferOrSeeOutgoingInvitations() {
        var group = join(service.create(auth(0)), 0, 1);
        invite(group, 0, 2);
        assertTrue(service.own(auth(1)).invitations().isEmpty());
        var id = group.id(); var rev = group.revision();
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> invite(service.own(auth(1)), 1, 3)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.remove(auth(1), id, rev, players.get(0).id())).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.transfer(auth(1), id, rev, players.get(1).id())).getStatusCode().value());
    }
    @Test void captainDeparturePromotesOldestRemainingMemberAndPreservesLobby() {
        var first = service.create(auth(0));
        var group = join(first, 0, 2);
        group = join(group, 0, 1);
        invite(group, 0, 3);
        var id = group.id(); var revision = group.revision(); var code = group.lobbyCode();
        conflict(() -> service.remove(auth(0), id, first.revision(), players.get(0).id()));
        assertNull(service.remove(auth(0), id, revision, players.get(0).id()));
        assertNull(service.own(auth(0)));
        var remaining = service.own(auth(2));
        assertEquals(players.get(2).id(), remaining.captainId());
        assertEquals(revision + 1, remaining.revision());
        assertEquals(2, remaining.members().size());
        assertEquals(code, remaining.lobbyCode());
        assertEquals(1, remaining.invitations().size());
        assertEquals(players.get(2).displayName(), service.incoming(auth(3)).getFirst().captainName());
        conflict(() -> service.remove(auth(1), id, revision, players.get(1).id()));
        assertEquals(3, service.join(auth(4), code).members().size());
    }
    @Test void rejoiningResetsSeniorityAndLastDepartureDeletesEmptyLobby() {
        var group = join(service.create(auth(0)), 0, 1);
        group = join(group, 0, 2);
        service.remove(auth(1), group.id(), group.revision(), players.get(1).id());
        group = service.join(auth(1), group.lobbyCode());
        service.remove(auth(0), group.id(), group.revision(), players.get(0).id());
        group = service.own(auth(2));
        assertEquals(players.get(2).id(), group.captainId());
        service.remove(auth(2), group.id(), group.revision(), players.get(2).id());
        group = service.own(auth(1));
        assertEquals(players.get(1).id(), group.captainId());
        invite(group, 1, 3);
        var code = group.lobbyCode();
        assertNull(service.remove(auth(1), group.id(), group.revision(), players.get(1).id()));
        assertNull(service.own(auth(1)));
        assertTrue(service.incoming(auth(3)).isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(4), code)).getStatusCode().value());
    }
    @Test void captainDepartureInvalidatesReadinessAndConcurrentDeparturesRequireReload() throws Exception {
        var group = join(service.create(auth(0)), 0, 1);
        var selection = select(1);
        group = service.readiness(auth(1), group.id(), group.revision(), true, season.getId(), season.getVersion(), selection.version());
        assertTrue(group.members().stream().filter(m -> !m.captain()).findFirst().orElseThrow().confirmed());
        var id = group.id(); var revision = group.revision();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Integer>>();
            for (int player = 0; player < 2; player++) {
                int index = player;
                results.add(executor.submit(() -> {
                    start.await();
                    try { service.remove(auth(index), id, revision, players.get(index).id()); return 200; }
                    catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
            var remaining = service.own(auth(0));
            if (remaining == null) remaining = service.own(auth(1));
            assertEquals(1, remaining.members().size());
            assertTrue(remaining.members().getFirst().captain());
            assertFalse(remaining.members().getFirst().confirmed());
        } finally { executor.shutdownNow(); }
    }
    @Test void noMemberCanCloseTheLobbyThroughTheRemovedEndpoint() throws Exception {
        var group = join(service.create(auth(0)), 0, 1);
        for (int player = 0; player < 2; player++) {
            mvc.perform(post("/api/v1/groups/" + group.id() + "/close").with(login(player)).with(csrf())
                .contentType("application/json").content("{\"expectedRevision\":" + group.revision() + "}"))
                .andExpect(status().isNotFound());
        }
        assertEquals(2, service.own(auth(1)).members().size());
    }
    @Test void concurrentInvitationsExpireIndependentlyAndAcceptingOneClearsTheOthers() throws Exception {
        var first=service.create(auth(0)); var second=service.create(auth(1));
        var before=Instant.now(); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();return invite(first,0,2);});
            var b=executor.submit(()->{start.await();return invite(second,1,2);});
            start.countDown(); a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        var after=Instant.now(); var pending=service.incoming(auth(2));
        assertEquals(2,pending.size());
        assertNotEquals(pending.get(0).id(),pending.get(1).id());
        assertEquals(Set.of(first.id(),second.id()),pending.stream().map(GroupService.Invitation::groupId).collect(java.util.stream.Collectors.toSet()));
        for(var invitation:pending) {
            assertFalse(invitation.expiresAt().isBefore(before.plusSeconds(60)));
            assertFalse(invitation.expiresAt().isAfter(after.plusSeconds(60)));
        }
        db.update("UPDATE rbgs.group_invitations SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",pending.getFirst().id());
        assertEquals(pending.getLast().id(),service.incoming(auth(2)).getFirst().id());
        assertEquals(1,service.incoming(auth(2)).size());
        conflict(()->service.accept(auth(2),pending.getFirst().id()));
        if(pending.getFirst().groupId().equals(first.id())) invite(first,0,2); else invite(second,1,2);
        assertEquals(2,service.incoming(auth(2)).size());
        var accepted=service.accept(auth(2),pending.getFirst().id());
        assertEquals(pending.getFirst().groupId(),accepted.id());
        assertTrue(service.incoming(auth(2)).isEmpty());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.accept(auth(2),pending.getLast().id())).getStatusCode().value());
        assertEquals(accepted.id(),service.own(auth(2)).id());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM rbgs.group_members WHERE account_id=?",Integer.class,players.get(2).id()));
    }
    @Test void renewalKeepsTheInvitationIdAndRestartsItsOneMinuteDeadline() {
        var group=service.create(auth(0)); invite(group,0,1);
        var old=service.incoming(auth(1)).getFirst();
        db.update("UPDATE rbgs.group_invitations SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",old.id());
        conflict(()->service.accept(auth(1),old.id()));
        var before=Instant.now(); invite(group,0,1); var after=Instant.now();
        var renewed=service.incoming(auth(1)).getFirst();
        assertEquals(old.id(),renewed.id());
        assertFalse(renewed.expiresAt().isBefore(before.plusSeconds(60)));
        assertFalse(renewed.expiresAt().isAfter(after.plusSeconds(60)));
        assertEquals(group.id(),service.accept(auth(1),renewed.id()).id());
    }
    @Test void expiredDeclinedAndCancelledInvitationsCannotJoin() {
        var group = service.create(auth(0));
        invite(group, 0, 1);
        var id = service.incoming(auth(1)).getFirst().id();
        db.update("UPDATE rbgs.group_invitations SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", id);
        conflict(() -> service.accept(auth(1), id));
        assertTrue(service.incoming(auth(1)).isEmpty());
        invite(group, 0, 1);
        service.decline(auth(1), id);
        assertThrows(ResponseStatusException.class, () -> service.accept(auth(1), id));
        invite(group, 0, 1);
        UUID next = service.incoming(auth(1)).getFirst().id();
        service.cancelInvitation(auth(0), group.id(), group.revision(), next);
        assertThrows(ResponseStatusException.class, () -> service.accept(auth(1), next));
    }
    @Test void defaultDpsLobbyRoleIsSharedWithoutChangingSoloSelectionOrRoster() {
        var selected = select(1);
        var group = join(service.create(auth(0)), 0, 1);
        long revision = group.revision();
        assertEquals(MatchRole.DPS, group.members().stream().filter(m -> m.accountId().equals(players.get(1).id())).findFirst().orElseThrow().role());
        var updated = service.displayRole(auth(1), group.id(), 0, MatchRole.FC);
        var shared = service.own(auth(0)).members().stream().filter(m -> m.accountId().equals(players.get(1).id())).findFirst().orElseThrow();
        assertEquals(MatchRole.FC, shared.role());
        assertEquals(1, shared.roleVersion());
        assertEquals(revision, updated.revision());
        assertEquals(selected.version(), shared.selectionVersion());
        assertEquals(MatchRole.HEALER, selections.context(auth(1)).selection().role());
        conflict(() -> service.displayRole(auth(1), group.id(), 0, MatchRole.DPS));
        assertEquals(MatchRole.FC, service.own(auth(1)).members().stream().filter(m -> m.accountId().equals(players.get(1).id())).findFirst().orElseThrow().role());
    }
    @Test void creationInviteAndCodeJoinAlwaysHaveRolesAndClearingIsRejected() throws Exception {
        var group=join(service.create(auth(0)),0,1);
        group=service.join(auth(2),group.lobbyCode());
        assertTrue(group.members().stream().allMatch(m->m.role()==MatchRole.DPS));
        String path="/api/v1/groups/"+group.id()+"/role";
        mvc.perform(post(path).with(login(1)).with(csrf()).contentType("application/json")
            .content("{\"expectedRoleVersion\":0,\"role\":\"HEALER\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(path).with(login(1)).with(csrf()).contentType("application/json")
            .content("{\"expectedRoleVersion\":1,\"role\":\"HEALER\"}"))
            .andExpect(status().isOk());
        for(String body:List.of("{\"expectedRoleVersion\":1,\"role\":null}","{\"expectedRoleVersion\":1}"))
            mvc.perform(post(path).with(login(1)).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        var member=service.own(auth(1)).members().stream().filter(m->m.accountId().equals(players.get(1).id())).findFirst().orElseThrow();
        assertEquals(MatchRole.HEALER,member.role()); assertEquals(1,member.roleVersion());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->
            db.update("UPDATE rbgs.group_members SET display_role=NULL WHERE account_id=?",players.get(1).id()));
        assertEquals("HEALER",db.queryForObject("SELECT display_role FROM rbgs.group_members WHERE account_id=?",String.class,players.get(1).id()));
    }
    @Test void displayRoleRequiresMembershipAuthenticationCsrfAndValidRole() throws Exception {
        var group = join(service.create(auth(0)), 0, 1);
        String path = "/api/v1/groups/" + group.id() + "/role";
        String body = "{\"expectedRoleVersion\":0,\"role\":\"HEALER\"}";
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(login(1)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(login(2)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post(path).with(login(1)).with(csrf()).contentType("application/json").content("{\"expectedRoleVersion\":0,\"role\":\"UNKNOWN\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(login(1)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        assertEquals(group.revision(), service.own(auth(0)).revision());
    }
    @Test void displayRoleNeedsNoSelectionAndResetsWithMembership() {
        var group = join(service.create(auth(0)), 0, 1);
        group = service.displayRole(auth(1), group.id(), 0, MatchRole.FC);
        assertNull(selections.context(auth(1)).selection());
        service.remove(auth(1), group.id(), group.revision(), players.get(1).id());
        var rejoined = service.join(auth(1), group.lobbyCode()).members().stream().filter(m -> m.accountId().equals(players.get(1).id())).findFirst().orElseThrow();
        assertEquals(MatchRole.DPS, rejoined.role());
        assertEquals(0, rejoined.roleVersion());
    }
    @Test void tenDistinctAccountsIsTheMaximumAndNineCannotBeQueueReady() {
        var group = service.create(auth(0));
        invite(group, 0, 10);
        UUID extra = service.incoming(auth(10)).getFirst().id();
        for (int i = 1; i < 9; i++) group = join(group, 0, i);
        assertEquals(9, group.members().size());
        assertFalse(group.queueReady());
        assertTrue(group.blockers().contains("TEN_PLAYERS_REQUIRED"));
        group = join(group, 0, 9);
        assertEquals(10, group.members().size());
        assertFalse(group.queueReady());
        assertTrue(group.members().stream().noneMatch(GroupService.Member::confirmed));
        assertFalse(group.blockers().contains("MEMBERS_NOT_CONFIRMED"));
        assertTrue(group.blockers().contains("MATCHMAKING_UNAVAILABLE"));
        conflict(() -> service.accept(auth(10), extra));
        assertEquals(group.id(), service.create(auth(1)).id());
    }
    @Test void selectionRosterAndCaptainChangesInvalidateConfirmation() {
        var selection = select(0);
        var group = service.create(auth(0));
        group = service.readiness(auth(0), group.id(), group.revision(), true, season.getId(), season.getVersion(), selection.version());
        assertTrue(group.members().getFirst().confirmed());
        assertTrue(group.members().getFirst().companionConnected());
        assertEquals("UNVERIFIED", group.members().getFirst().addonStatus());
        assertEquals("UNKNOWN", group.members().getFirst().gamePresence());
        db.update("UPDATE rbgs.season_selections SET match_role='DPS', version=version+1 WHERE account_id=?", players.get(0).id());
        assertFalse(service.own(auth(0)).members().getFirst().confirmed());
        final var before = group;
        conflict(() -> service.readiness(auth(0), before.id(), before.revision(), true, season.getId(), season.getVersion(), selection.version()));
        service.readiness(auth(0), group.id(), group.revision(), true, season.getId(), season.getVersion(), selection.version() + 1);
        group = join(group, 0, 1);
        assertFalse(group.members().stream().filter(m -> m.accountId().equals(players.get(0).id())).findFirst().orElseThrow().confirmed());
        service.readiness(auth(0), group.id(), group.revision(), true, season.getId(), season.getVersion(), selection.version() + 1);
        group = service.transfer(auth(0), group.id(), group.revision(), players.get(1).id());
        assertTrue(group.members().stream().noneMatch(GroupService.Member::confirmed));
    }
    @Test void withdrawalSeasonChangeAndDeviceRevocationRemainDistinctFromConfirmation() {
        var selection = select(0);
        var group = service.create(auth(0));
        service.readiness(auth(0), group.id(), group.revision(), true, season.getId(), season.getVersion(), selection.version());
        service.readiness(auth(0), group.id(), group.revision(), false, null, null, null);
        assertFalse(service.own(auth(0)).members().getFirst().confirmed());
        service.readiness(auth(0), group.id(), group.revision(), true, season.getId(), season.getVersion(), selection.version());
        db.update("UPDATE rbgs.devices SET revoked_at=CURRENT_TIMESTAMP WHERE account_id=?", players.get(0).id());
        var member = service.own(auth(0)).members().getFirst();
        assertFalse(member.companionConnected());
        assertTrue(member.blockers().contains("COMPANION_DISCONNECTED"));
        assertFalse(service.own(auth(0)).queueReady());
        db.update("UPDATE rbgs.seasons SET version=version+1 WHERE id=?", season.getId());
        assertFalse(service.own(auth(0)).members().getFirst().confirmed());
    }
    @Test void missingSeasonAndSelectionFailClosedAndMutationsValidateVersions() throws Exception {
        var group = service.create(auth(0));
        conflict(() -> service.readiness(auth(0), group.id(), group.revision(), true, season.getId(), season.getVersion(), 0L));
        when(settings.seasonId()).thenReturn(null);
        assertTrue(service.own(auth(0)).members().getFirst().blockers().contains("SEASON_UNAVAILABLE"));
        mvc.perform(post("/api/v1/groups/" + group.id() + "/captain").with(login(0)).with(csrf()).contentType("application/json")
                .content("{\"accountId\":\"" + players.get(1).id() + "\"}")).andExpect(status().isBadRequest());
    }
    @Test void concurrentCreatesAcrossDevicesReturnTheSameSingleGroup() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<GroupService.Group> create = () -> { start.await(); return service.create(auth(0)); };
            var a = executor.submit(create); var b = executor.submit(create); start.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS).id(), b.get(15, TimeUnit.SECONDS).id());
            assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.group_members WHERE account_id=?", Integer.class, players.get(0).id()));
        }
    }
    @Test void simultaneousAcceptsAcrossGroupsCannotDuplicateMembership() throws Exception {
        var a = service.create(auth(0)); var b = service.create(auth(1));
        invite(a, 0, 2); invite(b, 1, 2);
        var invites = service.incoming(auth(2));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (var invite : invites) results.add(executor.submit(() -> { start.await(); try { service.accept(auth(2), invite.id()); return true; } catch (ResponseStatusException rejected) { return false; } }));
            start.countDown();
            assertNotEquals(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.group_members WHERE account_id=?", Integer.class, players.get(2).id()));
        }
    }
    @Test void lobbyCodeJoinsAreAccountBoundIdempotentAndInvalidatePreparation() {
        var selected = select(0);
        var group = service.create(auth(0));
        assertTrue(group.lobbyCode().matches("[A-HJ-NP-Z2-9]{8}"));
        service.readiness(auth(0), group.id(), group.revision(), true, season.getId(), season.getVersion(), selected.version());
        var joined = service.join(auth(1), " " + group.lobbyCode().toLowerCase(Locale.ROOT) + " ");
        assertEquals(2, joined.members().size());
        assertTrue(joined.members().stream().noneMatch(GroupService.Member::confirmed));
        assertFalse(joined.queueReady());
        var repeated = service.join(auth(1), group.lobbyCode());
        assertEquals(joined.revision(), repeated.revision());
        assertEquals(2, repeated.members().size());
        var other = service.create(auth(2));
        assertEquals(other.id(), service.join(auth(1), other.lobbyCode()).id());
    }
    @Test void codeSwitchMovesOnlyTheCallerAndResetsTheirMembershipState() {
        var source = join(service.create(auth(0)), 0, 1);
        source = service.displayRole(auth(1), source.id(), 0, MatchRole.HEALER);
        var target = service.create(auth(2));
        var moved = service.join(auth(1), target.lobbyCode());
        assertEquals(target.id(), moved.id());
        assertEquals(players.get(2).id(), moved.captainId());
        assertEquals(target.revision() + 1, moved.revision());
        var member = moved.members().stream().filter(m -> m.accountId().equals(players.get(1).id())).findFirst().orElseThrow();
        assertEquals(MatchRole.DPS, member.role()); assertEquals(0, member.roleVersion()); assertFalse(member.confirmed());
        var old = service.own(auth(0));
        assertEquals(1, old.members().size()); assertEquals(source.revision() + 1, old.revision());
        assertEquals(source.lobbyCode(), old.lobbyCode());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM rbgs.group_members WHERE account_id=?", Integer.class, players.get(1).id()));
        assertTrue(db.queryForObject("SELECT joined_order FROM rbgs.group_members WHERE account_id=?", Long.class, players.get(1).id())
            > db.queryForObject("SELECT joined_order FROM rbgs.group_members WHERE account_id=?", Long.class, players.get(2).id()));
    }
    @Test void switchingCaptainPromotesOldestMemberWithoutClosingTheirLobby() {
        var source = join(service.create(auth(0)), 0, 2);
        source = join(source, 0, 1);
        invite(source, 0, 4);
        var target = service.create(auth(3));
        assertEquals(target.id(), service.join(auth(0), target.lobbyCode()).id());
        var old = service.own(auth(2));
        assertEquals(players.get(2).id(), old.captainId());
        assertEquals(source.revision() + 1, old.revision());
        assertEquals(2, old.members().size()); assertEquals(source.lobbyCode(), old.lobbyCode());
        assertEquals(1, old.invitations().size());
        assertEquals(players.get(2).displayName(), service.incoming(auth(4)).getFirst().captainName());
    }
    @Test void switchingLastMemberDeletesOnlyTheNowEmptySourceLobby() {
        var source = service.create(auth(0)); invite(source, 0, 2);
        var target = service.create(auth(1));
        service.join(auth(0), target.lobbyCode());
        assertFalse(db.queryForObject("SELECT EXISTS (SELECT 1 FROM rbgs.premade_groups WHERE id=?)", Boolean.class, source.id()));
        assertTrue(service.incoming(auth(2)).isEmpty());
        assertEquals(2, service.own(auth(1)).members().size());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(3), source.lobbyCode())).getStatusCode().value());
    }
    @Test void invalidDisabledOrFullDestinationNeverRemovesCurrentMembership() {
        var source = service.create(auth(0));
        var target = service.create(auth(1));
        for (int i = 2; i <= 10; i++) target = join(target, 1, i);
        String fullCode = target.lobbyCode();
        conflict(() -> service.join(auth(0), fullCode));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(0), "ZZZZZZZZ")).getStatusCode().value());
        service.code(auth(1), target.id(), target.revision(), false);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(0), fullCode)).getStatusCode().value());
        var retained = service.own(auth(0));
        assertEquals(source.id(), retained.id()); assertEquals(source.revision(), retained.revision());
        assertEquals(source.captainId(), retained.captainId()); assertEquals(source.lobbyCode(), retained.lobbyCode());
    }
    @Test void oppositeConcurrentSwitchesUseStableGroupLocksAndPreserveBothRosters() throws Exception {
        var a = join(service.create(auth(0)), 0, 2);
        var b = join(service.create(auth(1)), 1, 3);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return service.join(auth(0), b.lobbyCode()); });
            var second = executor.submit(() -> { start.await(); return service.join(auth(1), a.lobbyCode()); });
            start.countDown();
            assertEquals(b.id(), first.get(15, TimeUnit.SECONDS).id());
            assertEquals(a.id(), second.get(15, TimeUnit.SECONDS).id());
        }
        assertEquals(players.get(2).id(), service.own(auth(1)).captainId());
        assertEquals(players.get(3).id(), service.own(auth(0)).captainId());
        assertEquals(2, service.own(auth(0)).members().size());
        assertEquals(2, service.own(auth(1)).members().size());
    }
    @Test void captainControlsCodeRotationAndDisablingWithoutLeakingToOutsiders() throws Exception {
        var group = service.create(auth(0));
        var joined = service.join(auth(1), group.lobbyCode());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.code(auth(1), group.id(), joined.revision(), true)).getStatusCode().value());
        var rotated = service.code(auth(0), group.id(), joined.revision(), true);
        assertNotEquals(group.lobbyCode(), rotated.lobbyCode());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(2), group.lobbyCode())).getStatusCode().value());
        assertNull(service.code(auth(0), group.id(), rotated.revision(), false).lobbyCode());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(2), rotated.lobbyCode())).getStatusCode().value());
        var enabled = service.code(auth(0), group.id(), rotated.revision(), true);
        assertNotEquals(rotated.lobbyCode(), enabled.lobbyCode());
        mvc.perform(get("/api/v1/me/group").with(login(2))).andExpect(status().isOk()).andExpect(content().string(""));
        service.remove(auth(0), group.id(), enabled.revision(), players.get(0).id());
        var remaining = service.own(auth(1));
        assertEquals(enabled.lobbyCode(), remaining.lobbyCode());
        service.remove(auth(1), group.id(), remaining.revision(), players.get(1).id());
        assertNull(service.own(auth(1)));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(2), enabled.lobbyCode())).getStatusCode().value());
    }
    @Test void failedCodeAttemptsAreDurablyRateLimitedAcrossRequests() {
        for (int i = 0; i < 10; i++) {
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.join(auth(1), "invalid-code")).getStatusCode().value());
        }
        var group = service.create(auth(0));
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.join(auth(1), group.lobbyCode())).getStatusCode().value());
        db.update("UPDATE rbgs.group_join_limits SET window_start=CURRENT_TIMESTAMP - INTERVAL '61 seconds' WHERE account_id=?", players.get(1).id());
        assertEquals(group.id(), service.join(auth(1), group.lobbyCode()).id());
        assertEquals(1, db.queryForObject("SELECT attempts FROM rbgs.group_join_limits WHERE account_id=?", Integer.class, players.get(1).id()));
    }
    @Test void concurrentCodeJoinsForLastPlaceCannotExceedTenMembers() throws Exception {
        var group = service.create(auth(0));
        for (int i = 1; i < 9; i++) group = join(group, 0, i);
        final String code = group.lobbyCode();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 9; i < 11; i++) {
                final int player = i;
                results.add(executor.submit(() -> { start.await(); try { service.join(auth(player), code); return true; }
                    catch (ResponseStatusException rejected) { assertEquals(409, rejected.getStatusCode().value()); return false; } }));
            }
            start.countDown();
            assertNotEquals(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS));
            assertEquals(10, service.own(auth(0)).members().size());
        }
    }
    @Test void unresolvedMatchBlocksCodeAndInvitationJoinsUntilResolvedOrCancelled() {
        var group = service.create(auth(0));
        invite(group, 0, 1);
        var invitation = service.incoming(auth(1)).getFirst();
        var match = matches.saveAndFlush(new MatchEntity(players.get(1).id(), Instant.now(), Instant.now().plusSeconds(300), "[]",
                MatchMode.CONTROLLED_TEST, MatchEvidencePolicy.CAPTURE_ONLY, "DARKSPEAR_DIAGNOSTIC", Instant.now().plusSeconds(600)));
        fixtureMatches.add(match.getId());
        assignments.saveAndFlush(new MatchAssignmentEntity(match, players.get(1).id(), UUID.randomUUID(), null));
        conflict(() -> service.create(auth(1)));
        conflict(() -> service.join(auth(1), group.lobbyCode()));
        conflict(() -> service.accept(auth(1), invitation.id()));
        db.update("UPDATE rbgs.matches SET verification_status='REVIEW' WHERE id=?", match.getId());
        conflict(() -> service.join(auth(1), group.lobbyCode()));
        db.update("UPDATE rbgs.matches SET cancelled_at=CURRENT_TIMESTAMP WHERE id=?", match.getId());
        assertEquals(2, service.join(auth(1), group.lobbyCode()).members().size());
    }
    @Test void repeatedCreationReturnsCurrentMembershipWithoutChangingCaptainRosterOrCode() {
        var group = join(service.create(auth(0)), 0, 1);
        group = service.transfer(auth(0), group.id(), group.revision(), players.get(1).id());
        var repeated = service.create(auth(0));
        assertEquals(group.id(), repeated.id());
        assertEquals(group.captainId(), repeated.captainId());
        assertEquals(group.revision(), repeated.revision());
        assertEquals(group.lobbyCode(), repeated.lobbyCode());
        assertEquals(2, repeated.members().size());
    }
    @Test void concurrentAllocationsForDifferentGroupsProduceDistinctShortCodes() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return service.create(auth(0)); });
            var second = executor.submit(() -> { start.await(); return service.create(auth(1)); });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(a.lobbyCode(), b.lobbyCode());
            assertTrue(a.lobbyCode().matches("[A-HJ-NP-Z2-9]{8}"));
            assertTrue(b.lobbyCode().matches("[A-HJ-NP-Z2-9]{8}"));
        }
    }
    @Test void expiredCaptureOnlyReviewDoesNotReserveAnAccountButCompetitiveReviewStillDoes() {
        var old = Instant.now().minusSeconds(86400);
        var match = new MatchEntity(players.get(1).id(), old, old.plusSeconds(300), "[]", MatchMode.CONTROLLED_TEST,
                MatchEvidencePolicy.CAPTURE_ONLY, "DARKSPEAR_DIAGNOSTIC", old.plusSeconds(600));
        match.result("REVIEW", "CAPTURE_ONLY_NO_EXPECTED_TEAMS", null, null, 0, 0);
        matches.saveAndFlush(match);
        fixtureMatches.add(match.getId());
        assignments.saveAndFlush(new MatchAssignmentEntity(match, players.get(1).id(), UUID.randomUUID(), null));
        var group = service.create(auth(1));
        assertFalse(group.members().getFirst().blockers().contains("MATCH_UNRESOLVED"));
        assertEquals("REVIEW", matches.findById(match.getId()).orElseThrow().getVerificationStatus());
        service.remove(auth(1), group.id(), group.revision(), players.get(1).id());
        var target = service.create(auth(0));
        assertEquals(2, service.join(auth(1), target.lobbyCode()).members().size());
        var selected = select(1);
        group = service.own(auth(1));
        var confirmed = service.readiness(auth(1), group.id(), group.revision(), true, season.getId(), season.getVersion(), selected.version());
        assertTrue(confirmed.members().stream().filter(m -> m.accountId().equals(players.get(1).id())).findFirst().orElseThrow().confirmed());
        service.remove(auth(1), group.id(), group.revision(), players.get(1).id());
        db.update("UPDATE rbgs.matches SET mode='MATCHMADE' WHERE id=?", match.getId());
        conflict(() -> service.create(auth(1)));
        conflict(() -> service.join(auth(1), target.lobbyCode()));
    }
}
