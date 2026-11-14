package io.rbgs.api.matches;

import static io.rbgs.api.evidence.CaptureJson.*;
import io.rbgs.api.evidence.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MatchHistoryService {
    private final MatchAssignmentRepository assignments;
    private final MatchResultService results;
    private final MatchVerification verification;
    private final MatchDisputeService access;
    private final MatchDisputeRepository disputes;
    private final MatchHistoryReadRepository reads;
    private final MatchResultEventRepository events;
    private final CaptureEvidenceRepository evidence;
    private final CaptureJson json;
    private final MatchAvailability availability;

    public record Character(String name, String realm) {}
    public record FlagScore(long own, long opponent) {}
    public record Item(UUID matchId, Instant createdAt, String mode, String evidencePolicy, String map,
            Character character, String team, String result, MatchResultService.Result verification,
            String disputeStatus, boolean unread, boolean cancelled, FlagScore flagScore) {}
    public record Page(List<Item> items, String nextCursor) {}
    public record Player(String name, String realm, String team, boolean you) {}
    public record Stats(Long killingBlows, Long deaths, Long honorableKills, Long damage, Long healing, Long flagsCaptured, Long flagsReturned) {}
    public record Capture(Instant startedAt, Instant endedAt, Long durationSeconds, Stats stats,
            String delivery, String product, String build, int revision) {}
    public record Update(UUID id, Instant at, String status, String reason, String explanation, String outcome, String winningTeam) {}
    public record Detail(Item match, List<Player> roster, Capture capture, MatchDisputeService.Thread dispute,
            List<Update> updates, boolean olderUpdates, boolean canDispute) {}

    @Transactional(readOnly = true)
    public Page list(Authentication auth, String status, int limit, String cursor) {
        UUID account = access.activeAccount(auth, false);
        if (!Set.of("ALL", "PENDING", "REVIEW", "VERIFIED", "RESOLVED", "VOIDED", "CANCELLED", "WIN", "LOSS", "DRAW").contains(status)
                || limit < 1 || limit > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history filter or page size");
        var page = PageRequest.of(0, limit + 1);
        List<MatchAssignmentEntity> rows;
        if (cursor == null) rows = assignments.history(account, availability.controlledEnabled(), status, page);
        else {
            Instant before; UUID beforeId;
            try {
                if (cursor.length() > 256) throw new IllegalArgumentException();
                var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                before = Instant.parse(parts[0]); beforeId = uuid(parts[1]);
            } catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history cursor"); }
            rows = assignments.historyBefore(account, availability.controlledEnabled(), status, before, beforeId, page);
        }
        var visible = rows.subList(0, Math.min(rows.size(), limit));
        String next = null;
        if (rows.size() > limit) {
            var match = visible.getLast().getMatch();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((match.getCreatedAt() + "\n" + match.getId()).getBytes(StandardCharsets.UTF_8));
        }
        var wsgIds = visible.stream().filter(this::hasWarsongScore).map(MatchAssignmentEntity::getId).toList();
        Map<UUID, CaptureEvidenceEntity> reports = new HashMap<>();
        if (!wsgIds.isEmpty()) {
            for (var report : evidence.findLatestForAssignments(account, wsgIds)) {
                reports.put(report.getAssignment().getId(), report);
            }
        }
        return new Page(visible.stream().map(a -> item(a, reports.get(a.getId()))).toList(), next);
    }

    private boolean hasWarsongScore(MatchAssignmentEntity assignment) {
        return Set.of("FOREVER_WSG_TEST", "TBC_ANNIVERSARY_WSG_TEST").contains(assignment.getMatch().getProfile());
    }

    private Item item(MatchAssignmentEntity assignment, CaptureEvidenceEntity report) {
        var match = assignment.getMatch();
        var row = verification.expectedRoster(match).stream().filter(r -> assignment.getCharacterId().equals(uuid(r.get("characterId"))))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Stored character is unavailable"));
        var identity = object(row.get("character"));
        var thread = disputes.findByMatchIdAndAccountId(match.getId(), assignment.getAccountId()).orElse(null);
        boolean unreadResult = match.getDecisionAt() != null && reads.findById(assignment.getId())
                .map(r -> r.getSeenVersion() < match.getResultVersion()).orElse(true);
        String perspective = match.getVerificationStatus();
        if (match.getCancelledAt() != null) perspective = "CANCELLED";
        else if (Set.of("VERIFIED", "RESOLVED").contains(perspective)) {
            if ("DRAW".equals(match.getOutcomeKind())) perspective = "DRAW";
            else if (assignment.getAssignedTeam() == null) perspective = "REVIEW";
            else perspective = assignment.getAssignedTeam().equals(match.getWinningTeam()) ? "WIN" : "LOSS";
        }
        String map = switch (match.getProfile()) {
            case "DARKSPEAR_DIAGNOSTIC" -> "Darkspear Islands";
            case "FOREVER_WSG_TEST", "TBC_ANNIVERSARY_WSG_TEST" -> "Warsong Gulch";
            default -> null;
        };
        return new Item(match.getId(), match.getCreatedAt(), match.getMode().name(), match.getEvidencePolicy().name(), map,
                new Character((String)identity.get("name"), (String)identity.get("realm")), assignment.getAssignedTeam(), perspective,
                results.view(match), thread == null ? null : thread.getStatus(), unreadResult || (thread != null && thread.unread()), match.getCancelledAt() != null,
                hasWarsongScore(assignment) ? flagScore(report, identity, match.getProfile()) : null);
    }

    private FlagScore flagScore(CaptureEvidenceEntity report, Map<String, Object> identity, String profile) {
        if (report == null || "INVALID".equals(report.getValidationStatus())) return null;
        try {
            var payload = storedPayload(report);
            if (payload == null) return null;
            var capture = object(payload.get("capture"));
            if (!"COMPLETE".equals(capture.get("state"))) return null;
            var map = object(capture.get("map"));
            if (!profile.equals(map.get("profile")) || number(map.get("gameMapId")) != 489) return null;
            var observed = object(payload.get("observedCharacter"));
            if (!sameCharacter(identity, observed)) return null;
            return warsongScore(rows(capture.get("roster"), 1, 40), observed);
        } catch (ResponseStatusException | ClassCastException invalid) { return null; }
    }

    private static FlagScore warsongScore(List<?> roster, Map<String, Object> observed) {
        long horde = 0, alliance = 0;
        String ownTeam = null;
        for (Object entry : roster) {
            var row = object(entry);
            if (row.get("flagsCaptured") == null || !(row.get("observedTeamKey") instanceof String team)) return null;
            long flags = number(row.get("flagsCaptured"));
            switch (team) {
                case "0" -> horde += flags;
                case "1" -> alliance += flags;
                default -> { return null; }
            }
            if (sameCharacter(observed, object(row.get("character")))) {
                if (ownTeam != null) return null;
                ownTeam = team;
            }
        }
        if (ownTeam == null) return null;
        return "0".equals(ownTeam) ? new FlagScore(number(horde), number(alliance)) : new FlagScore(number(alliance), number(horde));
    }

    private static boolean sameCharacter(Map<String, Object> left, Map<String, Object> right) {
        return MatchVerification.normalizedName(left.get("name")).equals(MatchVerification.normalizedName(right.get("name")))
                && (left.get("realm") == null || right.get("realm") == null
                    || MatchVerification.normalizedName(left.get("realm")).equals(MatchVerification.normalizedName(right.get("realm"))));
    }

    @Transactional(readOnly = true)
    public Detail detail(Authentication auth, UUID id) {
        UUID account = access.activeAccount(auth, false);
        var assignment = access.participant(account, id);
        var match = assignment.getMatch();
        var roster = verification.expectedRoster(match).stream().map(row -> {
            var identity = object(row.get("character"));
            return new Player((String)identity.get("name"), (String)identity.get("realm"), (String)row.get("assignedTeamKey"),
                    assignment.getCharacterId().equals(uuid(row.get("characterId"))));
        }).toList();
        var thread = disputes.findByMatchIdAndAccountId(id, account).orElse(null);
        var history = events.findByMatchIdOrderByRecordedAtDescIdDesc(id, PageRequest.of(0, 101));
        var updates = new ArrayList<>(history.subList(0, Math.min(history.size(), 100)));
        Collections.reverse(updates);
        var reports = evidence.findByAccountIdAndAssignmentIdAndCaptureIdOrderByRevisionDesc(account, assignment.getId(), assignment.getCaptureId(), PageRequest.of(0, 1));
        var report = reports.isEmpty() ? null : reports.getFirst();
        return new Detail(item(assignment, report), roster, capture(assignment, report), thread == null ? null : access.view(thread),
                updates.stream().map(e -> new Update(e.getId(), e.getRecordedAt(), e.getStatus(), e.getReason(), e.getDecisionReason(), e.getOutcomeKind(), e.getWinningTeam())).toList(),
                history.size() > 100, match.getCancelledAt() == null && !"PENDING".equals(match.getVerificationStatus())
                        && (thread == null || "RESOLVED".equals(thread.getStatus())));
    }

    private Map<String, Object> storedPayload(CaptureEvidenceEntity report) {
        var bytes = report.getRedactedPayload().getBytes(StandardCharsets.UTF_8);
        return sha(bytes).equals(report.getStoredSha256()) ? json.parse(bytes) : null;
    }

    private Capture capture(MatchAssignmentEntity assignment, CaptureEvidenceEntity report) {
        if (report == null) return null;
        var received = new Capture(null, null, null, null, "RECEIVED", null, null, report.getRevision());
        try {
            var payload = storedPayload(report);
            if (payload == null) return received;
            var capture = object(payload.get("capture"));
            var client = object(payload.get("client"));
            Instant start = optionalTime(capture.get("startedAt")), end = optionalTime(capture.get("endedAt"));
            Long duration = start != null && end != null && end.isAfter(start) && Duration.between(start, end).toHours() <= 4 ? Duration.between(start, end).toSeconds() : null;
            Stats stats = null;
            if (!"INVALID".equals(report.getValidationStatus()) && "COMPLETE".equals(capture.get("state"))) {
                var own = object(payload.get("observedCharacter"));
                var declared = verification.expectedRoster(assignment.getMatch()).stream()
                        .filter(row -> assignment.getCharacterId().equals(uuid(row.get("characterId"))))
                        .map(row -> object(row.get("character"))).findFirst();
                boolean matchesAssignment = declared.isPresent() && sameCharacter(declared.get(), own);
                var matching = rows(capture.get("roster"), 1, 40).stream().map(CaptureJson::object)
                        .filter(row -> matchesAssignment && sameCharacter(own, object(row.get("character")))).toList();
                if (matching.size() == 1) {
                    var row = matching.getFirst();
                    stats = new Stats(stat(row.get("killingBlows")), stat(row.get("deaths")), stat(row.get("honorableKills")), stat(row.get("damage")), stat(row.get("healing")), stat(row.get("flagsCaptured")), stat(row.get("flagsReturned")));
                }
            }
            return new Capture(start, end, duration, stats, "RECEIVED", (String)client.get("product"), String.valueOf(client.get("build")), report.getRevision());
        } catch (ResponseStatusException | ClassCastException invalid) { return received; }
    }
    private Long stat(Object value) { return value instanceof Number n && n.longValue() >= 0 ? n.longValue() : null; }
    private Instant optionalTime(Object value) { return value == null ? null : timestamp(value); }
}
