package io.rbgs.api.matches;

import static io.rbgs.api.evidence.CaptureJson.*;

import io.rbgs.api.evidence.CaptureEvidenceEntity;
import io.rbgs.api.evidence.CaptureJson;
import io.rbgs.api.evidence.CaptureProfile;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MatchVerification {
    public static final String POLICY = "controlled-roster-v1";
    public static final int QUORUM = 6;
    public static final Duration REPORT_WINDOW = Duration.ofMinutes(15);
    private final CaptureJson json;

    public MatchVerification(CaptureJson json) { this.json = json; }

    public record Validation(String status, String reason, Map<String, Object> facts) {
        static Validation fail(String status, String reason) { return new Validation(status, reason, null); }
    }
    public record Decision(String status, String reason, String outcome, String winner, int a, int b) {}

    public Validation normalize(MatchEntity match, CaptureEvidenceEntity evidence) {
        if (!sha(evidence.getRedactedPayload().getBytes(StandardCharsets.UTF_8)).equals(evidence.getStoredSha256()))
            return Validation.fail("INVALID", "STORED_DATA_INTEGRITY");
        if (match.getCancelledAt() != null) return Validation.fail("UNVERIFIABLE", "ASSIGNMENT_CANCELLED");
        if (match.getEvidencePolicy() == MatchEvidencePolicy.CAPTURE_ONLY)
            return Validation.fail("UNVERIFIABLE", "CAPTURE_ONLY_NO_EXPECTED_TEAMS");
        var profile = CaptureProfile.find(match.getProfile()).orElse(null);
        if (profile == null) return Validation.fail("UNVERIFIABLE", "UNSUPPORTED_CAPTURE_PROFILE");
        Duration tolerance = profile.clockTolerance();
        try {
            var report = json.parse(evidence.getRedactedPayload().getBytes(StandardCharsets.UTF_8));
            var assignment = evidence.getAssignment();
            if (!assignment.getId().equals(uuid(report.get("assignmentId")))
                    || !match.getId().equals(uuid(report.get("matchId")))
                    || !evidence.getCaptureId().equals(uuid(report.get("captureId")))
                    || evidence.getRevision() != number(report.get("captureRevision"))
                    || !Objects.equals(assignment.getCodeHash(), report.get("assignmentCodeSha256")))
                return Validation.fail("INVALID", "ASSIGNMENT_BINDING_MISMATCH");
            var shape = new LinkedHashMap<>(report);
            shape.remove("assignmentCodeSha256");
            shape.put("assignmentCode", "0000000000000000");
            json.evidence(shape);
            var capture = object(report.get("capture"));
            if (!"COMPLETE".equals(capture.get("state"))) return Validation.fail("INCOMPLETE", "CAPTURE_INCOMPLETE");
            var map = object(capture.get("map"));
            if (!match.getProfile().equals(map.get("profile")) || number(map.get("gameMapId")) != profile.mapId()
                    || !profile.accepts(object(report.get("client"))))
                return Validation.fail("INVALID", "MAP_MISMATCH");
            Instant start = timestamp(capture.get("startedAt")), end = timestamp(capture.get("endedAt"));
            if (assignment.getIssuedAt() == null || start.isBefore(assignment.getIssuedAt().minus(tolerance))
                    || start.isBefore(match.getCreatedAt().minus(tolerance))
                    || start.isAfter(match.getCaptureStartDeadline().plus(tolerance)) || !end.isAfter(start)
                    || Duration.between(start, end).compareTo(profile.maxDuration()) > 0
                    || end.isAfter(evidence.getReceivedAt().plus(tolerance)))
                return Validation.fail("INVALID", "IMPLAUSIBLE_TIMING");

            var expected = expectedRoster(match);
            var observed = rows(capture.get("roster"), 1, 40);
            if (expected.size() != observed.size()) return Validation.fail("INVALID", "ROSTER_MISMATCH");
            Map<String, String> teams = new HashMap<>();
            Set<UUID> seen = new HashSet<>();
            Map<String, Object> own = null;
            for (Object item : observed) {
                var row = object(item);
                var identity = object(row.get("character"));
                var candidates = expected.stream().filter(e -> compatible(object(e.get("character")), identity)).toList();
                if (candidates.size() != 1) return Validation.fail("INVALID", "ROSTER_IDENTITY_AMBIGUOUS");
                var entry = candidates.getFirst();
                UUID character = uuid(entry.get("characterId"));
                if (!seen.add(character)) return Validation.fail("INVALID", "DUPLICATE_ROSTER_CHARACTER");
                String observedTeam = (String)row.get("observedTeamKey");
                String assignedTeam = (String)entry.get("assignedTeamKey");
                if (observedTeam == null || assignedTeam == null)
                    return Validation.fail("UNVERIFIABLE", "TEAM_MAPPING_UNKNOWN");
                String previous = teams.putIfAbsent(observedTeam, assignedTeam);
                if (previous != null && !previous.equals(assignedTeam)) return Validation.fail("INVALID", "TEAM_MAPPING_CONFLICT");
                if (character.equals(assignment.getCharacterId())) own = identity;
            }
            if (teams.size() != 2 || new HashSet<>(teams.values()).size() != 2)
                return Validation.fail("INVALID", "TEAM_MAPPING_CONFLICT");
            var declared = expected.stream().filter(e -> assignment.getCharacterId().equals(uuid(e.get("characterId")))).findFirst();
            var observedOwn = object(report.get("observedCharacter"));
            if (declared.isEmpty() || own == null
                    || !compatible(object(declared.get().get("character")), observedOwn)
                    || !compatible(own, observedOwn)
                    || !Objects.equals(assignment.getAssignedTeam(), declared.get().get("assignedTeamKey")))
                return Validation.fail("INVALID", "OWN_CHARACTER_MISMATCH");
            if (expected.stream().filter(e -> compatible(object(e.get("character")), observedOwn)).count() != 1)
                return Validation.fail("INVALID", "OWN_CHARACTER_AMBIGUOUS");

            var outcome = object(capture.get("outcome"));
            String kind = text(outcome.get("kind"));
            var facts = new LinkedHashMap<String, Object>();
            facts.put("policyVersion", POLICY);
            facts.put("mapProfile", match.getProfile());
            facts.put("gameMapId", profile.mapId());
            facts.put("outcome", kind);
            facts.put("winningTeam", "WIN".equals(kind) ? teams.get(text(outcome.get("winningObservedTeamKey"))) : null);
            facts.put("reporterTeam", assignment.getAssignedTeam());
            facts.put("startedAt", start.toString());
            facts.put("endedAt", end.toString());
            facts.put("teamMapping", teams);
            facts.put("rosterCharacterIds", seen.stream().map(UUID::toString).sorted().toList());
            return new Validation("VALID", null, facts);
        } catch (ResponseStatusException | ClassCastException error) {
            return Validation.fail("INVALID", "STORED_DATA_INVALID");
        }
    }

    public List<Map<String, Object>> expectedRoster(MatchEntity match) {
        return rows(json.parse(("{\"rows\":" + match.getExpectedRoster() + "}").getBytes(StandardCharsets.UTF_8)).get("rows"), 1, 40)
                .stream().map(CaptureJson::object).toList();
    }

    public static String normalizedName(Object value) {
        String name = Normalizer.normalize(text(value).strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) throw invalid();
        return name;
    }

    private static boolean compatible(Map<String, Object> expected, Map<String, Object> observed) {
        if (!normalizedName(expected.get("name")).equals(normalizedName(observed.get("name")))) return false;
        for (String key : List.of("realm", "guid")) {
            if (expected.get(key) != null && observed.get(key) != null
                    && !normalizedName(expected.get(key)).equals(normalizedName(observed.get(key)))) return false;
        }
        return true;
    }

    public Decision aggregate(MatchEntity match, List<CaptureEvidenceEntity> reports, Instant now) {
        String problem = null;
        Map<UUID, Map<String, Object>> independent = new HashMap<>();
        Map<UUID, Map<String, Object>> timely = new HashMap<>();
        List<Map<String, Object>> facts = new ArrayList<>();
        for (var report : reports) {
            if (Set.of("INVALID", "UNVERIFIABLE").contains(report.getValidationStatus())) {
                if (problem == null) problem = report.getReviewReason();
            } else if ("VALID".equals(report.getValidationStatus())) {
                var value = json.parse(report.getNormalizedFacts().getBytes(StandardCharsets.UTF_8));
                facts.add(value);
                independent.putIfAbsent(report.getAccountId(), value);
                if (match.getVerificationDeadline() == null || !report.getReceivedAt().isAfter(match.getVerificationDeadline()))
                    timely.putIfAbsent(report.getAccountId(), value);
            }
        }
        int a = (int)independent.values().stream().filter(f -> "A".equals(f.get("reporterTeam"))).count();
        int b = (int)independent.values().stream().filter(f -> "B".equals(f.get("reporterTeam"))).count();
        if (match.getCancelledAt() != null) problem = "ASSIGNMENT_CANCELLED";
        else if (match.getEvidencePolicy() == MatchEvidencePolicy.CAPTURE_ONLY) problem = "CAPTURE_ONLY_NO_EXPECTED_TEAMS";
        var profile = CaptureProfile.find(match.getProfile()).orElse(null);
        if (profile == null) problem = "UNSUPPORTED_CAPTURE_PROFILE";
        Duration tolerance = profile == null ? Duration.ZERO : profile.clockTolerance();
        String kind = null, winner = null;
        Instant earliestStart = null, latestStart = null, earliestEnd = null, latestEnd = null;
        for (var value : facts) {
            if (kind == null) { kind = text(value.get("outcome")); winner = (String)value.get("winningTeam"); }
            else if (!kind.equals(value.get("outcome")) || !Objects.equals(winner, value.get("winningTeam")))
                problem = "CONTRADICTORY_OUTCOME";
            Instant start = timestamp(value.get("startedAt")), end = timestamp(value.get("endedAt"));
            earliestStart = earliestStart == null || start.isBefore(earliestStart) ? start : earliestStart;
            latestStart = latestStart == null || start.isAfter(latestStart) ? start : latestStart;
            earliestEnd = earliestEnd == null || end.isBefore(earliestEnd) ? end : earliestEnd;
            latestEnd = latestEnd == null || end.isAfter(latestEnd) ? end : latestEnd;
        }
        if (earliestStart != null && (Duration.between(earliestStart, latestStart).compareTo(tolerance) > 0
                || Duration.between(earliestEnd, latestEnd).compareTo(tolerance) > 0)) problem = "CONTRADICTORY_TIMING";
        if (problem != null) return new Decision("REVIEW", problem, null, null, a, b);
        if ("REVIEW".equals(match.getVerificationStatus()))
            return new Decision("REVIEW", match.getVerificationReason(), null, null, a, b);
        long timelyA = timely.values().stream().filter(f -> "A".equals(f.get("reporterTeam"))).count();
        long timelyB = timely.values().stream().filter(f -> "B".equals(f.get("reporterTeam"))).count();
        if (!"VERIFIED".equals(match.getVerificationStatus()) && match.getVerificationDeadline() != null
                && !now.isBefore(match.getVerificationDeadline()) && (timelyA < QUORUM || timelyB < QUORUM))
            return new Decision("REVIEW", "INSUFFICIENT_REPORTS", null, null, a, b);
        if (a >= QUORUM && b >= QUORUM) return new Decision("VERIFIED", null, kind, winner, a, b);
        if (match.getVerificationDeadline() != null && !now.isBefore(match.getVerificationDeadline()))
            return new Decision("REVIEW", "INSUFFICIENT_REPORTS", null, null, a, b);
        return new Decision("PENDING", "AWAITING_REPORTS", null, null, a, b);
    }
}
