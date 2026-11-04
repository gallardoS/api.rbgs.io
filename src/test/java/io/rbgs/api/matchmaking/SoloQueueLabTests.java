package io.rbgs.api.matchmaking;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:55432/rbgs_soloq_lab_tests",
        "rbgs.soloq-lab.worker=false","server.address=127.0.0.1"})
@ActiveProfiles({"local","soloq-lab"})
@AutoConfigureMockMvc
class SoloQueueLabTests {
    @Autowired SoloQueueLab lab;
    @Autowired SoloQueueRooms rooms;
    @Autowired QueueTicketRepository tickets;
    @Autowired MockMvc mvc;
    @BeforeEach void reset() { lab.reset("NORMAL"); }

    @Test void realQueuePipelineProducesARecoverableRoomAndSupportsRequeue() {
        var initial=lab.snapshot(null); assertTrue(initial.simulation()); assertEquals(100,initial.players().size());
        UUID actor=initial.accountId(); lab.enter(actor); lab.enter(actor);
        var waiting=lab.snapshot(actor); assertEquals("QUEUED",waiting.ticket().state()); assertEquals(1,waiting.queueDepth());
        lab.cycle(); assertNull(lab.snapshot(actor).room());
        lab.addPlayers(actor,19); lab.cycle();
        var proposed=lab.snapshot(actor); assertEquals("PROPOSED",proposed.room().status());
        assertEquals(0,proposed.queueDepth());
        rooms.respondAt(actor,proposed.room().id(),true,java.time.Instant.now()); lab.acceptBots(actor,proposed.room().id());
        var prepared=lab.snapshot(actor); assertEquals("PREPARING",prepared.room().status());
        assertEquals(prepared.revision(),lab.snapshot(actor).revision());
        rooms.respondAt(actor,prepared.room().id(),false,java.time.Instant.now());
        assertNull(lab.snapshot(actor).ticket());
        assertEquals("CANCELLED",lab.snapshot(actor).room().status()); lab.enter(actor);
        assertEquals("QUEUED",lab.snapshot(actor).ticket().state());
    }
    @Test void hundredActorsProduceFiveRoomsAndMissingRolesNeverForceAProposal() {
        var state=lab.snapshot(null); lab.enter(state.accountId()); lab.addPlayers(state.accountId(),99);
        for (int i=0;i<5;i++) lab.cycle();
        Set<UUID> ids=new HashSet<>(); Set<UUID> participants=new HashSet<>();
        for (var player:state.players()) {
            var room=lab.snapshot(player.id()).room(); assertNotNull(room); ids.add(room.id());
            assertTrue(participants.add(player.id()));
        }
        assertEquals(5,ids.size()); assertEquals(100,participants.size());
        for (String scenario:List.of("NO_HEALERS")) {
            lab.reset(scenario); var snapshot=lab.snapshot(null); lab.enter(snapshot.accountId()); lab.addPlayers(snapshot.accountId(),19); lab.cycle();
            assertNull(lab.snapshot(snapshot.accountId()).room()); assertEquals(20,lab.snapshot(snapshot.accountId()).queueDepth());
        }
    }
    @Test void noVolunteersFormsARoomWithStableAutomaticCaptainsForNewPlayers() {
        lab.reset("NO_CAPTAINS");var initial=lab.snapshot(null);
        assertTrue(initial.players().stream().noneMatch(SoloQueueLab.Player::captainConsent));
        lab.enter(initial.accountId());lab.addPlayers(initial.accountId(),19);lab.cycle();
        var room=lab.snapshot(initial.accountId()).room();assertNotNull(room);assertEquals("PROPOSED",room.status());
        var captainA=room.teamA().stream().filter(SoloQueueRooms.Member::captain).findFirst().orElseThrow();
        var captainB=room.teamB().stream().filter(SoloQueueRooms.Member::captain).findFirst().orElseThrow();
        assertNotEquals(captainA.accountId(),captainB.accountId());
        assertEquals(room,lab.snapshot(initial.accountId()).room());
    }
    @Test void resetReleasesReservationsAndReplacesTheGeneration() {
        var old=lab.snapshot(null); lab.enter(old.accountId()); lab.addPlayers(old.accountId(),19); lab.cycle();
        lab.reset("NO_CAPTAINS"); var next=lab.snapshot(old.accountId());
        assertNotEquals(old.generation(),next.generation()); assertNull(next.ticket()); assertNull(next.room()); assertEquals(0,next.queueDepth());
    }
    @Test void localApiRequiresCsrfAndProductionAdmissionStaysClosed() throws Exception {
        mvc.perform(get("/api/v1/local/soloq")).andExpect(status().isOk()).andExpect(jsonPath("$.simulation").value(true));
        mvc.perform(post("/api/v1/local/soloq/queue")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/local/soloq/queue").with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.ticket.state").value("QUEUED"));
        mvc.perform(post("/api/v1/me/solo-queue").with(csrf())).andExpect(status().isUnauthorized());
    }
}
