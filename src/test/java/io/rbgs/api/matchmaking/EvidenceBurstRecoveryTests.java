package io.rbgs.api.matchmaking;

import io.rbgs.api.ApiApplication;
import io.rbgs.api.characters.selection.CharacterIdentity;
import io.rbgs.api.characters.selection.CharacterIdentityRepository;
import io.rbgs.api.characters.selection.MatchRole;
import io.rbgs.api.characters.selection.SeasonSelection;
import io.rbgs.api.characters.selection.SeasonSelectionRepository;
import io.rbgs.api.devices.DeviceService;
import io.rbgs.api.evidence.CaptureJson;
import io.rbgs.api.evidence.CaptureService;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.matches.MatchAssignmentEntity;
import io.rbgs.api.matches.MatchAssignmentRepository;
import io.rbgs.api.matches.MatchEntity;
import io.rbgs.api.matches.MatchEvidencePolicy;
import io.rbgs.api.matches.MatchMode;
import io.rbgs.api.matches.MatchRepository;
import io.rbgs.api.matches.MatchRoomEntity;
import io.rbgs.api.matches.MatchRoomRepository;
import io.rbgs.api.ratings.RatingResults;
import io.rbgs.api.seasons.RatingSubjectType;
import io.rbgs.api.seasons.SeasonEntity;
import io.rbgs.api.seasons.SeasonKind;
import io.rbgs.api.seasons.SeasonRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RBGS_F16_DB_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/rbgs_f16_evidence")
class EvidenceBurstRecoveryTests {
    private static final int DISTINCT_REPORTS = 190;
    private static final String ISSUER = "https://f16-evidence.invalid";
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @Timeout(180)
    void pendingBurstSurvivesRestartAndRatesOnlyVerifiedMatches() throws Exception {
        Path report = Path.of("target", "f16-evidence.json");
        Files.deleteIfExists(report);
        List<Report> submissions = new ArrayList<>();
        List<UUID> matches = new ArrayList<>();
        List<Ack> acknowledgements;
        try (var io = Executors.newVirtualThreadPerTaskExecutor();
             var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .executor(io).connectTimeout(Duration.ofSeconds(5)).build()) {
            try (var first = start(3600000)) {
                var db = first.getBean(JdbcTemplate.class);
                assertEquals(0, count(db, "accounts"), "Start with an empty F16 evidence database");
                seed(first, submissions, matches);
                assertEquals(DISTINCT_REPORTS, submissions.size());
                submissions.addAll(List.copyOf(submissions.subList(0, 10)));
                var warmup = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(first) + "/api/v1/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                assertEquals(200, http.send(warmup, HttpResponse.BodyHandlers.discarding()).statusCode());
                acknowledgements = burst(http, port(first), submissions);
                assertEquals(200, acknowledgements.size());
                assertEquals(DISTINCT_REPORTS, acknowledgements.stream().map(Ack::id).distinct().count());
                for (int i = 0; i < 10; i++) assertEquals(acknowledgements.get(i).id(), acknowledgements.get(DISTINCT_REPORTS + i).id());
                assertEquals(DISTINCT_REPORTS, count(db, "capture_processing_jobs"));
                assertEquals(0, count(db, "rating_result_revisions"));
                db.update("UPDATE rbgs.matches SET verification_deadline = clock_timestamp() WHERE id = ?", matches.get(8));
            }

            try (var restarted = start(500)) {
                var db = restarted.getBean(JdbcTemplate.class);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                while (count(db, "capture_processing_jobs") > 0 && System.nanoTime() < deadline) Thread.sleep(250);
                assertEquals(0, count(db, "capture_processing_jobs"));
                assertEquals(DISTINCT_REPORTS, count(db, "capture_evidence"));
                assertEquals(160, db.queryForObject("SELECT count(*) FROM rbgs.competitive_ratings WHERE games_played = 1", Integer.class));
                assertEquals(0, db.queryForObject("SELECT count(*) FROM rbgs.competitive_ratings WHERE games_played > 1", Integer.class));
                assertEquals(8, count(db, "rating_result_revisions"));
                for (int i = 0; i < matches.size(); i++) assertOutcome(db, matches.get(i), i);

                for (int i = 0; i < 10; i++) {
                    var submission = submissions.get(i);
                    var request = request(port(restarted), "/api/v1/evidence/receipts/" + submission.assignment
                            + "/" + submission.capture + "/1", submission.token).GET().build();
                    var recovered = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, recovered.statusCode());
                    assertEquals(acknowledgements.get(i).id(), json.readTree(recovered.body()).path("evidenceId").asText());
                    assertEquals(acknowledgements.get(i).id(), submit(http, port(restarted), submission).id());
                }
                assertEquals(DISTINCT_REPORTS, count(db, "capture_evidence"));
                assertEquals(0, count(db, "capture_processing_jobs"));
                assertEquals(8, count(db, "rating_result_revisions"));
                var latencies = QueueCoordinatorTimings.stats(acknowledgements.stream().map(Ack::elapsedNs).toList());
                var reportData = Map.ofEntries(Map.entry("submissions", acknowledgements.size()),
                        Map.entry("distinctReports", DISTINCT_REPORTS), Map.entry("duplicatesInBurst", 10),
                        Map.entry("missingReports", 10), Map.entry("contradictoryMatches", 1),
                        Map.entry("verifiedMatches", 8), Map.entry("ratedParticipants", 160),
                        Map.entry("receiptsRecoveredAndResentAfterRestart", 10),
                        Map.entry("payloadBytes", submissions.stream().mapToInt(s -> s.body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).max().orElseThrow()),
                        Map.entry("acknowledgement", latencies));
                Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(reportData));
                assertTrue(latencies.get("p95Ms").longValue() < 1000, "Durable acknowledgement missed the 1 s p95 objective");
            }
        }
    }

    private void seed(ConfigurableApplicationContext api, List<Report> submissions, List<UUID> matches) throws Exception {
        var accounts = api.getBean(AccountService.class);
        var characters = api.getBean(CharacterIdentityRepository.class);
        var selections = api.getBean(SeasonSelectionRepository.class);
        var confirmed = api.getBean(ConfirmedRatingStore.class);
        var devices = api.getBean(DeviceService.class);
        var seasons = api.getBean(SeasonRepository.class);
        var captures = api.getBean(CaptureService.class);
        var captureJson = api.getBean(CaptureJson.class);
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/capture/evidence-wire.sanitized.json")) {
            fixture = input.readAllBytes();
        }
        Map<RatingSubjectType, UUID> seasonIds = new java.util.EnumMap<>(RatingSubjectType.class);
        for (var type : RatingSubjectType.values()) {
            var season = new SeasonEntity("F16 " + type, SeasonKind.BETA, type);
            season.start(Instant.now().minusSeconds(60));
            seasonIds.put(type, seasons.saveAndFlush(season).getId());
        }
        for (int game = 0; game < 10; game++) {
            var type = game % 2 == 0 ? RatingSubjectType.ACCOUNT : RatingSubjectType.CHARACTER;
            UUID season = seasonIds.get(type);
            var queue = game < 5 ? ConfirmedRatingStore.Queue.SOLO : ConfirmedRatingStore.Queue.PREMADE;
            List<Reporter> players = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String name = "F16 Player " + (game * 20 + i);
                String subject = "fixture-" + (game * 20 + i);
                UUID account = accounts.upsert(ISSUER, subject, name).id();
                UUID character = characters.saveAndFlush(CharacterIdentity.declared(account, name, "FixtureRealm")).getId();
                var selection = new SeasonSelection(account, season);
                selection.choose(character, type, MatchRole.DPS, false);
                confirmed.initializeSelection(selections.saveAndFlush(selection).getId());
                var pairing = devices.start(DeviceService.CAPTURE_SCOPE);
                devices.approve(auth(subject), pairing.code(), true);
                var token = devices.exchange(pairing.pollingSecret()).token();
                var candidate = new SoloQueueCandidate(account, character, season, "EU", "fixture", "fixture",
                        MatchRole.DPS, false, Instant.now(), 1500, "WARRIOR", 0);
                players.add(new Reporter(candidate, Map.of("name", name, "realm", "FixtureRealm"), token, i < 10 ? "A" : "B"));
            }

            var assignments = freeze(api, players, season, type, queue);
            UUID match = assignments.getFirst().getMatch().getId();
            matches.add(match);
            var scoreboard = players.stream().map(p -> Map.of("character", p.character, "observedTeamKey", p.team.equals("A") ? "0" : "1",
                    "damage", 12000, "healing", 3500, "deaths", 3, "honorableKills", 12, "killingBlows", 4)).toList();
            for (int i = 0; i < (game == 8 ? 10 : 20); i++) {
                var player = players.get(i);
                var assignment = assignments.get(i);
                var command = captures.issue(player.candidate.accountId(), assignment.getCaptureId());
                var evidence = captureJson.parse(fixture);
                evidence.put("matchId", match.toString());
                evidence.put("assignmentId", assignment.getId().toString());
                evidence.put("captureId", assignment.getCaptureId().toString());
                evidence.put("assignmentCode", command.get("assignmentCode"));
                evidence.put("captureRevision", 1);
                evidence.put("observedCharacter", player.character);
                var capture = CaptureJson.object(evidence.get("capture"));
                capture.put("startedAt", Instant.now().minusSeconds(30).toString());
                capture.put("endedAt", Instant.now().minusSeconds(1).toString());
                capture.put("outcome", Map.of("kind", "WIN", "winningObservedTeamKey", game == 9 && i == 19 ? "1" : "0"));
                capture.put("roster", scoreboard);
                submissions.add(new Report(assignment.getId(), assignment.getCaptureId(), player.token, captureJson.encode(evidence)));
            }
        }
    }

    private List<MatchAssignmentEntity> freeze(ConfigurableApplicationContext api, List<Reporter> players,
            UUID season, RatingSubjectType type, ConfirmedRatingStore.Queue queue) {
        return api.getBean(TransactionTemplate.class).execute(transaction -> {
            var roster = players.stream().map(p -> Map.of("accountId", p.candidate.accountId(), "characterId", p.candidate.characterId(),
                    "character", p.character, "assignedTeamKey", p.team)).toList();
            Instant now = Instant.now();
            UUID captainA = players.getFirst().candidate.accountId(), captainB = players.get(10).candidate.accountId();
            var proposal = new SoloQueueMatcher.Proposal(players.subList(0, 10).stream().map(Reporter::candidate).toList(),
                    players.subList(10, 20).stream().map(Reporter::candidate).toList(), captainA, captainB,
                    new SoloQueueMatcher.Composition(0, 0, 10));
            UUID roomId = api.getBean(QueueProposalRepository.class).saveAndFlush(new QueueProposal(proposal, now)).getId();
            // Use a recognized diagnostic capture profile for this synthetic competitive fixture only.
            var match = api.getBean(MatchRepository.class).saveAndFlush(new MatchEntity(captainA, now, now.plusSeconds(600),
                    json.writeValueAsString(roster), MatchMode.MATCHMADE, MatchEvidencePolicy.FULL_ROSTER,
                    "DARKSPEAR_DIAGNOSTIC", now.plusSeconds(3600)));
            var room = new MatchRoomEntity(roomId, season, captainA, captainB, now, "FOREVER");
            room.linkMatch(match.getId());
            api.getBean(MatchRoomRepository.class).saveAndFlush(room);
            var assignments = players.stream().map(p -> new MatchAssignmentEntity(match, p.candidate.accountId(), p.candidate.characterId(), p.team)).toList();
            api.getBean(MatchAssignmentRepository.class).saveAllAndFlush(assignments);
            api.getBean(RatingResults.class).freeze(match.getId(), roomId, season, queue, type, proposal);
            return assignments;
        });
    }

    private List<Ack> burst(HttpClient http, int port, List<Report> reports) throws Exception {
        var ready = new CountDownLatch(reports.size());
        var send = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var uploads = reports.stream().map(report -> workers.submit(() -> {
                ready.countDown();
                send.await();
                return submit(http, port, report);
            })).toList();
            boolean allReady = ready.await(5, TimeUnit.SECONDS);
            send.countDown();
            assertTrue(allReady, "Could not start the synchronized upload burst");
            var acks = new ArrayList<Ack>();
            for (var upload : uploads) acks.add(upload.get(30, TimeUnit.SECONDS));
            return acks;
        }
    }

    private Ack submit(HttpClient http, int port, Report report) throws Exception {
        var request = request(port, "/api/v1/evidence", report.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(report.body)).build();
        long started = System.nanoTime();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.nanoTime() - started;
        assertEquals(200, response.statusCode(), response::body);
        JsonNode receipt = json.readTree(response.body());
        assertTrue(receipt.path("durablyStored").asBoolean());
        return new Ack(receipt.path("evidenceId").asText(), elapsed);
    }

    private void assertOutcome(JdbcTemplate db, UUID match, int index) {
        var result = db.queryForMap("SELECT verification_status, verification_reason, winning_team FROM rbgs.matches WHERE id = ?", match);
        String expectedStatus = index < 8 ? "VERIFIED" : "REVIEW";
        assertEquals(expectedStatus, result.get("verification_status"), result::toString);
        assertEquals(index < 8 ? "A" : null, result.get("winning_team"));
        if (index == 8) assertEquals("INSUFFICIENT_REPORTS", result.get("verification_reason"));
        if (index == 9) assertEquals("CONTRADICTORY_OUTCOME", result.get("verification_reason"));
        assertEquals(index < 8 ? 20 : 0, db.queryForObject(
                "SELECT count(*) FROM rbgs.rating_movements WHERE replay_match_id = ? AND match_id = ?", Integer.class, match, match));
    }

    private ConfigurableApplicationContext start(int processingDelay) {
        return new SpringApplicationBuilder(ApiApplication.class).run("--spring.datasource.url=" + System.getenv("RBGS_F16_DB_URL"),
                "--spring.datasource.username=" + System.getenv().getOrDefault("RBGS_F16_DB_USER", "rbgs"),
                "--spring.datasource.password=" + System.getenv().getOrDefault("RBGS_F16_DB_PASSWORD", ""),
                "--spring.datasource.hikari.maximum-pool-size=12", "--server.address=127.0.0.1", "--server.port=0",
                "--server.tomcat.accept-count=256",
                "--management.server.port=-1", "--rbgs.rating.enabled=true", "--rbgs.matches.processing-delay-ms=" + processingDelay,
                "--rbgs.notifications.sending-enabled=false", "--rbgs.play.preseason-preview=false");
    }

    private static OAuth2AuthenticationToken auth(String subject) {
        Instant now = Instant.now();
        var token = OidcIdToken.withTokenValue("f16-fixture").issuer(ISSUER).subject(subject).issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        var roles = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(roles, token), roles, "battle-net");
    }

    private static int port(ConfigurableApplicationContext api) {
        return api.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT count(*) FROM rbgs." + table, Integer.class);
    }

    private static HttpRequest.Builder request(int port, String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + token);
    }

    private record Reporter(SoloQueueCandidate candidate, Map<String, String> character, String token, String team) {}
    private record Report(UUID assignment, UUID capture, String token, String body) {}
    private record Ack(String id, long elapsedNs) {}
}
