package io.rbgs.api.matchmaking;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.characters.selection.CharacterIdentityRepository;
import io.rbgs.api.characters.selection.MatchRole;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.groups.GroupMember;
import io.rbgs.api.groups.GroupMemberRepository;
import io.rbgs.api.groups.PremadeGroup;
import io.rbgs.api.groups.PremadeGroupRepository;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.seasons.RatingSubjectType;
import io.rbgs.api.seasons.SeasonRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.SessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RBGS_F16_DB_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/rbgs_f16_(smoke|quick|ramp|soak|sse)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${RBGS_F16_DB_URL}",
        "spring.datasource.username=${RBGS_F16_DB_USER:rbgs}",
        "spring.datasource.password=${RBGS_F16_DB_PASSWORD:}",
        "spring.datasource.hikari.maximum-pool-size=12",
        "management.server.port=0",
        "management.prometheus.metrics.export.enabled=true",
        "server.address=127.0.0.1",
        "server.servlet.session.cookie.secure=false",
        "rbgs.play.season-id=45b0ddbd-7bae-46f3-acb3-56b8f2adfa85",
        "rbgs.play.preseason-preview=true",
        "rbgs.soloq.worker=false",
        "rbgs.premade.worker=false",
        "rbgs.soloq.sse.timeout-ms=${RBGS_F16_SSE_TIMEOUT_MS:0}",
        "rbgs.notifications.sending-enabled=false",
        "rbgs.leaderboard.public-enabled=true",
        "rbgs.rating.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(QueueCoordinatorTimings.class)
@SuppressWarnings({"rawtypes", "unchecked"}) // The JDBC session implementation is package-private.
class PreseasonLoadSmokeTests {
    private static final String QUEUE = "/api/v1/me/solo-queue";
    private static final String PREMADE = "/api/v1/me/premade-queue";
    private static final String ISSUER = "https://f16-smoke.invalid";
    private static final int PLAYERS = 20;

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    @Autowired JdbcTemplate db;
    @Autowired AccountService accounts;
    @Autowired CharacterIdentityRepository characters;
    @Autowired SeasonSelectionRepository selections;
    @Autowired SeasonRepository seasons;
    @Autowired ConfirmedRatingStore ratings;
    @Autowired SessionRepository sessions;
    @Autowired DeviceService devices;
    @Autowired SoloQueueStore store;
    @Autowired PremadeQueueStore premadeStore;
    @Autowired PremadeGroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired QueueTicketRepository tickets;
    @Autowired PremadeQueueEntryRepository entries;
    @Autowired TransactionTemplate transactions;
    @Autowired HikariDataSource dataSource;
    @Autowired SoloQueueRooms rooms;
    @Autowired SoloQueueEvents events;
    @Autowired MeterRegistry meters;
    @Autowired QueueCoordinatorTimings timings;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, ConcurrentLinkedQueue<Double>> latencies = new ConcurrentHashMap<>();

    @Test
    @EnabledIfEnvironmentVariable(named = "RBGS_F16_DB_URL", matches = ".*/rbgs_f16_smoke")
    @Timeout(120)
    void twentyAuthenticatedPlayersReceiveAcceptAndRequeue() throws Exception {
        assertEquals(0, count("accounts"), "Start with an empty dedicated F16 database");
        Files.deleteIfExists(Path.of("target", "f16-smoke.json"));
        var players = new ArrayList<Player>();
        try (var client = http) {
            var season = seasons.findById(PreseasonQueue.SEASON_ID).orElseThrow();
            for (int i = 0; i < PLAYERS; i++) players.add(player(i, season.getVersion()));
            try {
                parallel(players, Player::open);
                assertEquals(PLAYERS, events.activeConnections());

                enterAndDrain(players);
                UUID declined = store.proposeReal().orElseThrow().getId();
                parallel(players, p -> p.awaitProposal(declined));
                players.getFirst().respond(declined, true);
                players.get(1).respond(declined, false);
                assertReleased(players, declined, "PARTICIPANT_DECLINED");
                parallel(players, Player::awaitCancellation);

                timings.measure("burst-enter");
                enterAndDrain(players);
                timings.measure(null);
                Player reconnecting = players.getFirst();
                reconnecting.disconnect();
                assertEquals(PLAYERS - 1, events.activeConnections());
                UUID accepted = store.proposeReal().orElseThrow().getId();
                assertNotEquals(declined, accepted);
                parallel(players.subList(1, PLAYERS), p -> p.awaitProposal(accepted));
                reconnecting.open();
                assertProposal(reconnecting.status(), accepted);
                assertEquals(PLAYERS, events.activeConnections());

                timings.measure("burst-accept");
                parallel(players, p -> p.respond(accepted, true));
                timings.measure(null);
                JsonNode ready = reconnecting.status();
                assertEquals("PREPARING", ready.path("room").path("status").asText());
                assertTrue(ready.path("matchId").isNull());
                var deadline = Instant.parse(ready.path("room").path("preparationDeadline").asText());
                rooms.expireReal(deadline);
                assertReleased(players, accepted, PreseasonQueue.COMPLETED);
                parallel(players, p -> p.await("changed"));
                timings.measure("staggered-enter");
                staggered(players, Player::enter);
                timings.measure(null);
                assertEquals(PLAYERS, reservations());
                UUID staggered = store.proposeReal().orElseThrow().getId();
                timings.measure("staggered-accept");
                staggered(players, p -> p.respond(staggered, true));
                timings.measure(null);
                completeRehearsal(players, staggered);
                assertEquals(0, count("matches"));
                assertMetrics(client, 21);
                players.forEach(Player::disconnect);

                rehearsePremade(players);
                assertMetrics(client, 42);
                writeReport();
            } finally {
                players.forEach(Player::disconnect);
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RBGS_F16_DB_URL", matches = ".*/rbgs_f16_sse")
    @Timeout(120)
    void queueStreamsRecoverAfterDisconnectOrExpiry() throws Exception {
        assertEquals(0, count("accounts"), "Start with an empty dedicated F16 database");
        boolean expiry = Long.parseLong(System.getenv().getOrDefault("RBGS_F16_SSE_TIMEOUT_MS", "0")) == 3000;
        var results = new java.util.LinkedHashMap<String, StreamRelease>();
        var players = new ArrayList<Player>();
        try (var client = http) {
            var season = seasons.findById(PreseasonQueue.SEASON_ID).orElseThrow();
            for (int i = 0; i < PLAYERS; i++) players.add(player(i, season.getVersion()));
            try {
                parallel(players, Player::enter);
                parallel(players, Player::openWire);
                assertEquals(PLAYERS, events.activeConnections());
                long started = System.nanoTime();
                if (expiry) parallel(players, Player::awaitWireEnd);
                else parallel(players, p -> p.closeWire(false));
                var first = awaitStreamRelease(started);
                results.put(expiry ? "expiry" : "fin", first);
                if (expiry) assertEquals(PLAYERS, closedStreams("timeout"));
                if (first.remaining != 0) players.forEach(Player::disconnect);

                UUID proposal = store.proposeReal().orElseThrow().getId();
                parallel(players, p -> { p.openWire(); assertProposal(p.status(), proposal); });
                assertEquals(PLAYERS, events.activeConnections());

                if (!expiry) {
                    started = System.nanoTime();
                    parallel(players, p -> p.closeWire(true));
                    var reset = awaitStreamRelease(started);
                    results.put("reset", reset);
                    if (reset.remaining != 0) players.forEach(Player::disconnect);

                    players.getFirst().respond(proposal, false);
                    parallel(players, Player::openWire);
                    assertReleased(players, proposal, "PARTICIPANT_DECLINED");
                    Player revoked = players.getFirst();
                    sessions.deleteById(revoked.session);
                    revoked.awaitWireEnd();
                    assertEquals(PLAYERS - 1, events.activeAccounts());
                    assertEquals(1, closedStreams("unauthorized"));
                }
                var report = Map.of("accounts", PLAYERS, "cleanup", results);
                Files.writeString(Path.of("target", "f16-sse-" + (expiry ? "expiry" : "transport") + ".json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                for (var result : results.values()) {
                    assertEquals(0, result.remaining);
                    assertTrue(result.elapsedMs < 12_000, "SSE cleanup took " + result.elapsedMs + " ms");
                }
            } finally {
                players.forEach(Player::disconnect);
            }
        }
    }

    private StreamRelease awaitStreamRelease(long started) throws InterruptedException {
        long elapsed;
        int remaining;
        do {
            elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            remaining = events.activeConnections();
            if (remaining == 0 || elapsed >= 27_000) break;
            Thread.sleep(50);
        } while (true);
        return new StreamRelease(elapsed, remaining);
    }

    private record StreamRelease(long elapsedMs, int remaining) {}

    private int closedStreams(String reason) {
        return (int) meters.get("rbgs.queue.sse.closed").tag("reason", reason).counter().count();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RBGS_F16_PROFILE", matches = "quick|ramp|soak")
    @Timeout(2700)
    void mixedQueuesUnderK6() throws Exception {
        String profile = System.getenv("RBGS_F16_PROFILE");
        assertTrue(System.getenv("RBGS_F16_DB_URL").endsWith("/rbgs_f16_" + profile));
        assertEquals(0, count("accounts"), "Start with an empty dedicated F16 database");
        Path executable = Path.of(System.getenv().getOrDefault("RBGS_F16_K6", "k6"));
        var season = seasons.findById(PreseasonQueue.SEASON_ID).orElseThrow();
        var solo = new ArrayList<Player>();
        var premade = new ArrayList<Player>();
        for (int i = 0; i < 200; i++) (i < 100 ? solo : premade).add(player(i, season.getVersion()));
        assignPremadeGroups(premade);
        var fixtures = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i += 10) {
            solo.subList(i, i + 10).forEach(p -> fixtures.add(p.k6Fixture()));
            premade.subList(i + 1, i + 10).forEach(p -> fixtures.add(p.k6Fixture()));
            fixtures.add(premade.get(i).k6Fixture()); // Activate the captain after the rest of the lobby.
        }

        Path target = Path.of("target").toAbsolutePath();
        Path summary = target.resolve("f16-k6-" + profile + ".json");
        Path telemetry = target.resolve("f16-telemetry-" + profile + ".json");
        Path fixture = Files.createTempFile(target, "f16-accounts-", ".json");
        var samples = new ArrayList<Map<String, Object>>();
        Files.deleteIfExists(summary);
        Files.deleteIfExists(telemetry);
        try (var workers = Executors.newSingleThreadScheduledExecutor()) {
            Files.writeString(fixture, json.writeValueAsString(fixtures));
            var soloWorker = new SoloQueueWorker(store, rooms, tickets);
            var premadeWorker = new PremadeQueueWorker(premadeStore, entries);
            var soloTask = workers.scheduleWithFixedDelay(soloWorker::cycle, 0, 2, TimeUnit.SECONDS);
            var premadeTask = workers.scheduleWithFixedDelay(premadeWorker::cycle, 0, 2, TimeUnit.SECONDS);
            var builder = new ProcessBuilder(executable.toString(), "run", "--no-color", "load/preseason.js");
            builder.environment().put("RBGS_F16_BASE_URL", "http://127.0.0.1:" + port);
            builder.environment().put("RBGS_F16_FIXTURES", fixture.toString());
            builder.environment().put("RBGS_F16_SUMMARY", summary.toString());
            builder.environment().put("K6_NO_USAGE_REPORT", "true");
            builder.redirectErrorStream(true).redirectOutput(target.resolve("f16-k6-" + profile + ".log").toFile());
            Process k6 = builder.start();
            long started = System.nanoTime();
            try {
                while (!k6.waitFor(5, TimeUnit.SECONDS)) samples.add(telemetrySample(started));
                for (int i = 0; events.activeConnections() != 0 && i < 5; i++) {
                    Thread.sleep(5000);
                    samples.add(telemetrySample(started));
                }
                assertWorkerRunning(soloTask);
                assertWorkerRunning(premadeTask);
            } finally {
                if (k6.isAlive()) k6.destroyForcibly().waitFor();
                soloTask.cancel(false);
                premadeTask.cancel(false);
                workers.shutdown();
                workers.awaitTermination(20, TimeUnit.SECONDS);
            }

            int duplicates = db.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT account_id FROM rbgs.queue_tickets WHERE state IN ('QUEUED', 'PROPOSED')
                        GROUP BY account_id HAVING count(*) > 1
                    ) duplicates
                    """, Integer.class);
            var outcomes = db.queryForList("""
                    SELECT t.queue_type, r.cancellation_reason, count(DISTINCT r.id) AS rooms
                    FROM rbgs.match_rooms r JOIN rbgs.queue_tickets t ON t.proposal_id = r.id
                    GROUP BY t.queue_type, r.cancellation_reason ORDER BY t.queue_type, r.cancellation_reason
                    """);
            int remainingStreams = events.activeConnections();
            drainReservations();
            var peakSamples = peakStreams(profile, samples);
            long atCapacity = peakSamples.stream().filter(sample ->
                    (int) sample.get("streams") == 200 && (int) sample.get("accounts") == 200).count();
            var report = Map.ofEntries(
                    Map.entry("profile", profile), Map.entry("logicalUsers", 200), Map.entry("samples", samples),
                    Map.entry("peakStreams", samples.stream().mapToInt(s -> (int) s.get("streams")).max().orElse(0)),
                    Map.entry("peakHoldSamples", peakSamples.size()), Map.entry("peakHoldSamplesAtCapacity", atCapacity),
                    Map.entry("minimumStreamsInPeakHold", peakSamples.stream().mapToInt(s -> (int) s.get("streams")).min().orElse(0)),
                    Map.entry("minimumAccountsInPeakHold", peakSamples.stream().mapToInt(s -> (int) s.get("accounts")).min().orElse(0)),
                    Map.entry("streamClosures", streamClosures()),
                    Map.entry("roomOutcomes", outcomes), Map.entry("duplicateReservations", duplicates),
                    Map.entry("streamsAfterRampDown", remainingStreams), Map.entry("reservationsAfterCleanup", reservations()),
                    Map.entry("k6ExitCode", k6.exitValue()), Map.entry("postgresql", db.queryForObject("SHOW server_version", String.class)),
                    Map.entry("java", System.getProperty("java.version")), Map.entry("os", System.getProperty("os.name")),
                    Map.entry("availableProcessors", Runtime.getRuntime().availableProcessors()));
            Files.writeString(telemetry, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            assertEquals(0, duplicates);
            assertEquals(0, remainingStreams);
            assertEquals(0, reservations());
            assertEquals(0, count("matches"));
            assertTrue((int) report.get("peakStreams") >= 200, "Check the SSE time series in " + telemetry);
            assertTrue(Files.isRegularFile(summary), "Check the k6 log under target");
            assertTrue(atCapacity > 0 && atCapacity == peakSamples.size(), "SSE capacity was below 200 in some peak samples");
            assertEquals(0, k6.exitValue(), "Check the k6 thresholds in " + summary);
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private Map<String, Integer> streamClosures() {
        var results = new java.util.TreeMap<String, Integer>();
        for (var counter : meters.find("rbgs.queue.sse.closed").counters()) {
            results.put(counter.getId().getTag("reason"), (int) counter.count());
        }
        return results;
    }

    private static List<Map<String, Object>> peakStreams(String profile, List<Map<String, Object>> samples) {
        int holdStart = switch (profile) {
            case "quick" -> 40;
            case "ramp" -> 300;
            case "soak" -> 120;
            default -> throw new IllegalArgumentException("Unknown F16 profile");
        };
        int holdDuration = switch (profile) {
            case "quick" -> 90;
            case "ramp" -> 600;
            case "soak" -> 1800;
            default -> throw new IllegalArgumentException("Unknown F16 profile");
        };
        return samples.stream().filter(sample -> {
            long seconds = (long) sample.get("seconds");
            return seconds >= holdStart + 5 && seconds < holdStart + holdDuration;
        }).toList();
    }

    private Map<String, Object> telemetrySample(long started) {
        var pool = dataSource.getHikariPoolMXBean();
        long gcMs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
        int coordinatorWaiters = db.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                    AND wait_event_type = 'Lock' AND query LIKE '%queue_coordinator%FOR UPDATE%'
                """, Integer.class);
        return Map.of("seconds", (System.nanoTime() - started) / 1_000_000_000,
                "streams", events.activeConnections(), "accounts", events.activeAccounts(), "activeDbConnections", pool.getActiveConnections(),
                "pendingDbConnections", pool.getThreadsAwaitingConnection(), "coordinatorWaiters", coordinatorWaiters,
                "heapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), "gcMs", gcMs,
                "threads", ManagementFactory.getThreadMXBean().getThreadCount(), "reservations", reservations());
    }

    private static void assertWorkerRunning(java.util.concurrent.Future<?> worker) throws Exception {
        if (!worker.isDone()) return;
        try {
            worker.get();
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Queue worker failed during load", failure.getCause());
        }
        fail("Queue worker stopped before load completed");
    }

    private void drainReservations() {
        db.queryForList("SELECT id FROM rbgs.match_rooms WHERE status IN ('PROPOSED','PREPARING')", UUID.class)
                .forEach(id -> rooms.cancelRoom(id, "F16_TEST_ENDED"));
        entries.findBySourceAndStateOrderByEnteredAtAscIdAsc("FOREVER", "QUEUED")
                .forEach(entry -> premadeStore.cancel(entry.candidate().captainId(), entry.getId()));
        tickets.waiting().forEach(ticket -> store.cancel(ticket.getAccountId(), ticket.getId()));
    }

    private void rehearsePremade(List<Player> players) throws Exception {
        List<Player> captains = assignPremadeGroups(players);
        parallel(players, Player::open);
        assertEquals(PLAYERS, events.activeConnections());
        Player captain = captains.getFirst();
        Player removed = players.get(1);
        removed.status();
        removed.rest("premade-member-cannot-enter", PREMADE, captain.admission, 403);
        captain.enter();
        assertEquals(10, reservations());
        captain.rest("premade-remove-member", "/api/v1/groups/" + captain.group + "/members/remove",
                json.writeValueAsString(Map.of("expectedRevision", captain.status().path("groupRevision").asLong(),
                        "accountId", removed.account)), 200);
        assertEquals(0, reservations());
        assertEquals("LOBBY_CHANGED", captain.status().path("ticket").path("cancellationReason").asText());
        members.saveAndFlush(new GroupMember(removed.account, captain.group));
        captain.refreshGroupAdmission();

        parallel(captains, Player::enter);
        assertEquals(PLAYERS, reservations());
        UUID declined = premadeStore.proposeReal().orElseThrow().getId();
        parallel(players, p -> p.awaitProposal(declined));
        removed.respond(declined, false);
        assertReleased(players, declined, "PARTICIPANT_DECLINED");

        parallel(captains, Player::enter);
        UUID expired = premadeStore.proposeReal().orElseThrow().getId();
        var deadline = Instant.parse(captain.status().path("room").path("acceptanceDeadline").asText());
        rooms.expireReal(deadline);
        assertReleased(players, expired, "DEADLINE_EXPIRED");

        parallel(captains, Player::enter);
        removed.disconnect();
        UUID accepted = premadeStore.proposeReal().orElseThrow().getId();
        parallel(players.stream().filter(player -> player != removed).toList(), p -> p.awaitProposal(accepted));
        removed.open();
        assertProposal(removed.status(), accepted);
        parallel(players, p -> p.respond(accepted, true));
        completeRehearsal(players, accepted);
        assertEquals(0, count("matches"));
    }

    private List<Player> assignPremadeGroups(List<Player> players) {
        List<Player> captains = new ArrayList<>();
        for (int i = 0; i < players.size(); i += 10) {
            Player captain = players.get(i);
            var roster = players.subList(i, i + 10);
            transactions.executeWithoutResult(status -> {
                var group = groups.saveAndFlush(new PremadeGroup(captain.account, "EU"));
                for (Player player : roster) {
                    members.saveAndFlush(new GroupMember(player.account, group.getId()));
                    player.queue = PREMADE;
                    player.group = group.getId();
                    player.admission = null;
                }
            });
            captain.refreshGroupAdmission();
            captains.add(captain);
        }
        return captains;
    }

    private void completeRehearsal(List<Player> players, UUID proposal) throws Exception {
        JsonNode ready = players.getFirst().status();
        assertEquals("PREPARING", ready.path("room").path("status").asText());
        assertTrue(ready.path("matchId").isNull());
        rooms.expireReal(Instant.parse(ready.path("room").path("preparationDeadline").asText()));
        assertReleased(players, proposal, PreseasonQueue.COMPLETED);
    }

    private void staggered(List<Player> players, Action action) throws Exception {
        for (Player player : players) {
            action.run(player);
            Thread.sleep(75);
        }
    }

    private void enterAndDrain(List<Player> players) throws Exception {
        parallel(players, p -> { p.enter(); p.await("changed"); });
        assertEquals(PLAYERS, reservations());
    }

    private int reservations() {
        return db.queryForObject("SELECT count(*) FROM rbgs.queue_tickets WHERE state IN ('QUEUED', 'PROPOSED')", Integer.class);
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM rbgs." + table, Integer.class);
    }

    private void assertReleased(List<Player> players, UUID proposal, String reason) throws Exception {
        parallel(players, p -> {
            JsonNode status = p.status();
            assertEquals(proposal.toString(), status.path("room").path("id").asText());
            assertEquals(reason, status.path("room").path("cancellationReason").asText());
            assertEquals("CANCELLED", status.path("ticket").path("state").asText());
        });
        assertEquals(0, reservations());
    }

    private void assertProposal(JsonNode status, UUID proposal) {
        JsonNode room = status.path("room");
        assertEquals(proposal.toString(), room.path("id").asText());
        assertEquals("PROPOSED", room.path("status").asText());
        assertTrue(room.path("preseasonPreview").asBoolean());
        assertEquals(10, room.path("teamA").size());
        assertEquals(10, room.path("teamB").size());
    }

    private void assertMetrics(HttpClient client, int opens) throws Exception {
        var scrape = client.send(request(managementPort, "/actuator/prometheus").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, scrape.statusCode());
        assertTrue(scrape.body().contains("rbgs_queue_sse_active 20.0"));
        assertTrue(scrape.body().contains("rbgs_queue_sse_opened_total " + opens + ".0"));
        assertTrue(scrape.body().contains("rbgs_queue_sse_invalidations_total "));
        assertTrue(scrape.body().contains("rbgs_soloq_wait_seconds_bucket"));
        assertTrue(scrape.body().contains("http_server_requests_seconds_bucket"));
        var publicScrape = client.send(request(port, "/actuator/prometheus").GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertNotEquals(200, publicScrape.statusCode());
    }

    private void writeReport() throws IOException {
        var report = new java.util.TreeMap<String, Object>();
        report.put("scenario", "preseason-soloq-and-premade-smoke");
        report.put("logicalUsers", PLAYERS);
        report.put("concurrentStreams", PLAYERS);
        report.put("proposals", 6);
        report.put("reconnectsVerified", 2);
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name"));
        report.put("postgresql", db.queryForObject("SHOW server_version", String.class));
        var results = new java.util.TreeMap<String, Object>();
        latencies.forEach((operation, values) -> {
            var sorted = values.stream().sorted().toList();
            double p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
            results.put(operation, Map.of("samples", sorted.size(), "p95Ms", Math.round(p95)));
        });
        report.put("http", results);
        var coordinator = timings.report();
        assertEquals(4, coordinator.size());
        report.put("soloQueueServer", coordinator);
        Files.writeString(Path.of("target", "f16-smoke.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private Player player(int index, long seasonVersion) {
        String subject = "f16-" + index;
        UUID account = accounts.upsert(ISSUER, subject, "Load Player " + index).id();
        String name = "LoadPlayer" + index;
        var character = characters.saveAndFlush(CharacterIdentity.declared(account, name, "Load Realm"));
        var token = OidcIdToken.withTokenValue("f16-fixture")
                .issuer(ISSUER).subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(7200)).build();
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var authentication = new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, token), authorities, "battle-net");
        var pairing = devices.start(DeviceService.CAPTURE_SCOPE);
        devices.approve(authentication, pairing.code(), true);
        var device = devices.exchange(pairing.pollingSecret());
        devices.heartbeat("Bearer " + device.token());
        db.update("""
                INSERT INTO rbgs.play_characters(account_id, namespace, wow_account_id, realm_id, character_id, region, name, realm)
                VALUES (?, 'profile-forever-eu', 1, 1, ?, 'EU', ?, 'Load Realm')
                """, account, index + 1, name);
        var selection = new SeasonSelection(account, PreseasonQueue.SEASON_ID);
        int roleIndex = index % 20;
        MatchRole role = roleIndex < 2 ? MatchRole.FC : roleIndex < 8 ? MatchRole.HEALER : MatchRole.DPS;
        selection.choose(character.getId(), RatingSubjectType.ACCOUNT, role, false);
        selection.useDevice(device.deviceId());
        selection = selections.saveAndFlush(selection);
        ratings.initializeSelection(selection.getId());

        var session = sessions.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        sessions.save(session);
        return new Player(account, session.getId(), device.token(),
                json.writeValueAsString(new SoloQueueService.Admission(PreseasonQueue.SEASON_ID, seasonVersion, selection.getVersion())));
    }

    @FunctionalInterface
    private interface Action {
        void run(Player player) throws Exception;
    }

    private void parallel(List<Player> players, Action action) throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = players.stream()
                    .map(player -> (Callable<Void>) () -> {
                        action.run(player);
                        return null;
                    }).toList();
            for (var result : workers.invokeAll(tasks, 20, TimeUnit.SECONDS)) result.get();
        }
    }

    private static HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
    }

    private final class Player {
        private final UUID account;
        private final String session, deviceToken;
        private String queue = QUEUE;
        private String admission;
        private UUID group;
        private String csrf;
        private BufferedReader stream;
        private Socket wire;
        private BufferedReader wireReader;

        private Player(UUID account, String session, String deviceToken, String admission) {
            this.account = account;
            this.session = session;
            this.deviceToken = deviceToken;
            this.admission = admission;
        }

        void open() throws Exception {
            var response = http.send(request(port, queue + "/events").header("Accept", "text/event-stream")
                    .header("Cookie", cookies()).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            stream = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode());
            await("ready");
        }

        void openWire() throws IOException {
            closeWire(false);
            wire = new Socket();
            wire.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            wire.setSoTimeout(10000);
            wire.getOutputStream().write(("GET " + queue + "/events HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nAccept: text/event-stream\r\nConnection: close\r\nCookie: " + cookies() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            wireReader = new BufferedReader(new InputStreamReader(wire.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(wireReader.readLine().startsWith("HTTP/1.1 200 "));
            boolean ready = false;
            for (String line; (line = wireReader.readLine()) != null;) {
                if (ready && line.isEmpty()) return;
                if ("event:ready".equals(line)) ready = true;
            }
            fail("SSE socket ended before ready");
        }

        void awaitWireEnd() throws IOException {
            while (wireReader.readLine() != null) { }
            closeWire(false);
        }

        void closeWire(boolean reset) throws IOException {
            if (wire == null) return;
            if (reset) wire.setSoLinger(true, 0);
            wire.close();
            wire = null;
            wireReader = null;
        }

        void awaitProposal(UUID proposal) throws Exception {
            for (;;) {
                await("changed");
                JsonNode status = status();
                if (status.path("room").path("id").asText().equals(proposal.toString())) {
                    assertProposal(status, proposal);
                    return;
                }
            }
        }

        void awaitCancellation() throws Exception {
            do {
                await("changed");
            } while (!"CANCELLED".equals(status().path("room").path("status").asText()));
        }

        Map<String, Object> k6Fixture() {
            var fixture = new java.util.LinkedHashMap<String, Object>();
            fixture.put("session", session);
            fixture.put("deviceToken", deviceToken);
            fixture.put("queue", queue.equals(QUEUE) ? "soloq" : "premade");
            fixture.put("admission", admission);
            return fixture;
        }

        void await(String event) throws IOException {
            for (String line; (line = stream.readLine()) != null;) {
                if (line.equals("event:" + event)) return;
            }
            fail("SSE ended while awaiting " + event);
        }

        void disconnect() {
            events.closeAccount(account);
            try { closeWire(false); } catch (IOException ignored) { }
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
                stream = null;
            }
        }

        JsonNode status() throws Exception { return rest("status", queue, null, 200); }

        void refreshGroupAdmission() {
            long revision = groups.findById(group).orElseThrow().getRevision();
            admission = json.writeValueAsString(new PremadeQueueService.Admission(group, revision));
        }

        void enter() throws Exception {
            if (csrf == null) status();
            rest("enter", queue, admission, 200);
        }

        void respond(UUID proposal, boolean accept) throws Exception {
            rest(accept ? "accept" : "decline", queue + "/rooms/" + proposal + "/response", "{\"accept\":" + accept + "}", 200);
        }

        private JsonNode rest(String operation, String path, String body, int expected) throws Exception {
            var request = request(port, path).header("Cookie", cookies());
            if (body != null) request.header("X-XSRF-TOKEN", csrf).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            long start = System.nanoTime();
            var result = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            latencies.computeIfAbsent((queue.equals(QUEUE) ? "soloq-" : "premade-") + operation, ignored -> new ConcurrentLinkedQueue<>())
                    .add((System.nanoTime() - start) / 1_000_000.0);
            assertEquals(expected, result.statusCode(), () -> operation + ": " + result.body());
            result.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("XSRF-TOKEN="))
                    .findFirst().ifPresent(value -> csrf = value.substring("XSRF-TOKEN=".length(), value.indexOf(';')));
            return json.readTree(result.body());
        }

        private String cookies() {
            return "JSESSIONID=" + session + (csrf == null ? "" : "; XSRF-TOKEN=" + csrf);
        }
    }
}
