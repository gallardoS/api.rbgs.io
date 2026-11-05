package io.rbgs.api.matchmaking;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:55432/rbgs_soloq_lab_tests",
        "rbgs.soloq-lab.worker=false","server.address=127.0.0.1"})
@ActiveProfiles({"local","soloq-lab"}) @AutoConfigureMockMvc
class PremadeQueueLabTests {
    @Autowired PremadeQueueLab lab;
    @Autowired SoloQueueLab soloLab;
    @Autowired SoloQueueRooms rooms;
    @Autowired QueueTicketRepository tickets;
    @Autowired MockMvc mvc;
    @BeforeEach void reset() { lab.reset(); }
    private UUID actor() { return lab.setup(null).accountId(); }
    private void enter(UUID actor) {
        var state=lab.status(actor);lab.enter(actor,new PremadeQueueService.Admission(state.groupId(),state.groupRevision()));
    }
    @Test void captainSearchCancelRequeueAndRecoverableTwentyPlayerAcceptance() {
        UUID actor=actor();enter(actor);enter(actor);
        assertEquals(1,lab.setup(actor).queueDepth());assertEquals("QUEUED",lab.status(actor).ticket().state());
        lab.cancel(actor,lab.status(actor).ticket().id());assertEquals(0,lab.setup(actor).queueDepth());
        enter(actor);lab.addOpponents(actor,1);lab.cycle();
        var state=lab.status(actor);assertEquals("PROPOSED",state.room().status());assertTrue(state.simulation());
        lab.acceptBots(actor,state.room().id());assertEquals("PROPOSED",lab.status(actor).room().status());
        assertEquals(19,java.util.stream.Stream.concat(lab.status(actor).room().teamA().stream(),lab.status(actor).room().teamB().stream()).filter(SoloQueueRooms.Member::accepted).count());
        lab.respond(actor,state.room().id(),true);assertEquals("PREPARING",lab.status(actor).room().status());
        assertEquals(lab.status(actor).revision(),lab.status(actor).revision());
        lab.respond(actor,state.room().id(),false);assertEquals("CANCELLED",lab.status(actor).room().status());
        enter(actor);assertEquals("QUEUED",lab.status(actor).ticket().state());
    }
    @Test void membersSeeSameProposalButCannotStartOrCancelGroupSearch() {
        UUID captain=actor();var own=lab.status(captain).groupId();
        UUID member=lab.setup(captain).players().stream().filter(p->p.groupId().equals(own)&&!p.captain()).findFirst().orElseThrow().id();
        assertFalse(lab.status(member).canControl());assertThrows(SecurityException.class,()->enter(member));
        enter(captain);UUID entry=lab.status(member).ticket().id();
        assertThrows(SecurityException.class,()->lab.cancel(member,entry));
        lab.addOpponents(captain,1);lab.cycle();assertEquals(lab.status(captain).room().id(),lab.status(member).room().id());
        lab.respond(member,lab.status(member).room().id(),false);
        assertEquals("CANCELLED",lab.status(captain).ticket().state());
    }
    @Test void timeoutReleasesBothGroupsAndResetReplacesGeneration() {
        UUID actor=actor();enter(actor);lab.addOpponents(actor,1);lab.cycle();
        var room=lab.status(actor).room();rooms.expire(Instant.parse(room.acceptanceDeadline().toString()).plusSeconds(1));
        assertEquals("CANCELLED",lab.status(actor).room().status());assertEquals("DEADLINE_EXPIRED",lab.status(actor).room().cancellationReason());
        for(var p:java.util.stream.Stream.concat(room.teamA().stream(),room.teamB().stream()).toList())assertFalse(tickets.hasReservation(p.accountId()));
        UUID generation=lab.setup(actor).generation();lab.reset();assertNotEquals(generation,lab.setup(actor).generation());assertNull(lab.status(actor).ticket());
    }
    @Test void hundredPremadePlayersMakeFiveRoomsWithoutAffectingSoloDepth() {
        soloLab.reset("NORMAL");UUID actor=actor();enter(actor);lab.addOpponents(actor,9);
        assertEquals(10,lab.setup(actor).queueDepth());assertEquals(0,soloLab.snapshot(null).queueDepth());
        for(int i=0;i<5;i++)lab.cycle();
        var roomIds=new HashSet<UUID>();var accounts=new HashSet<UUID>();
        for(var p:lab.setup(actor).players()) { var state=lab.status(p.id());assertNotNull(state.room());roomIds.add(state.room().id());assertTrue(accounts.add(p.id())); }
        assertEquals(5,roomIds.size());assertEquals(100,accounts.size());
    }
    @Test void labApiRequiresCsrfAndRestrictsActorsAndForeignRooms() throws Exception {
        var setup=mvc.perform(get("/api/v1/local/premade/setup")).andExpect(status().isOk()).andExpect(jsonPath("$.players.length()").value(100)).andReturn();
        var cookie=Arrays.stream(setup.getResponse().getCookies()).filter(c->c.getName().equals("SESSION")||c.getName().equals("RBGS_SOLOQ_LAB")).findFirst().orElseThrow();
        var state=lab.status(actor());String body="{\"groupId\":\""+state.groupId()+"\",\"groupRevision\":"+state.groupRevision()+"}";
        mvc.perform(post("/api/v1/local/premade").cookie(cookie).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/local/premade").cookie(cookie).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.ticket.state").value("QUEUED"));
        mvc.perform(post("/api/v1/local/premade/actor").cookie(cookie).with(csrf()).contentType("application/json").content("{\"accountId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/local/premade/rooms/"+UUID.randomUUID()+"/response").cookie(cookie).with(csrf()).contentType("application/json").content("{\"accept\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/premade-queue")).andExpect(status().isUnauthorized());
    }
    @Test void captainChangeCancelsImmediatelyWithoutRunningMatcherAndNeverAutoRequeues() {
        UUID actor=actor();enter(actor);var before=lab.status(actor);
        UUID next=lab.setup(actor).players().stream().filter(p->p.groupId().equals(before.groupId())&&!p.captain()).findFirst().orElseThrow().id();
        lab.transferCaptain(actor,next);
        assertEquals("CANCELLED",lab.status(actor).ticket().state());assertEquals("LOBBY_CHANGED",lab.status(actor).ticket().cancellationReason());
        assertEquals(0,lab.setup(actor).queueDepth());assertFalse(lab.status(actor).canControl());assertTrue(lab.status(next).canControl());
        assertThrows(SecurityException.class,()->enter(actor));
        enter(next);assertEquals("QUEUED",lab.status(next).ticket().state());
        lab.addOpponents(next,1);lab.cycle();assertEquals("PROPOSED",lab.status(next).room().status());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->lab.transferCaptain(next,actor));
    }
}
