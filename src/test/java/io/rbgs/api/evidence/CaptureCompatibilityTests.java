package io.rbgs.api.evidence;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CaptureCompatibilityTests {
    private final CaptureJson json = new CaptureJson();

    private Map<String, Object> payload() throws Exception {
        try (var source = getClass().getResourceAsStream("/capture/evidence-wire.sanitized.json")) {
            return json.parse(source.readAllBytes());
        }
    }

    @Test void future_patches_are_accepted_without_rewriting_client_metadata() throws Exception {
        for (String adapter : new String[] {"darkspear-scoreboard-v1", "darkspear-70338-test-1"}) {
            var value = payload();
            var client = CaptureJson.object(value.get("client"));
            client.put("gameVersion", "1.61.0");
            client.put("gameBuild", "100001");
            client.put("interface", 16100);
            client.put("adapterVersion", adapter);
            byte[] before = json.canonical(value);
            assertDoesNotThrow(() -> json.evidence(value));
            assertArrayEquals(before, json.canonical(value));
        }
        assertDoesNotThrow(() -> json.evidence(payload()));
    }

    @Test void malformed_metadata_unknown_adapters_and_other_products_remain_invalid() throws Exception {
        for (var invalid : Map.<String, Object>of("gameVersion", "1..61", "gameBuild", "not-a-build",
                "interface", 0, "adapterVersion", "unknown-adapter", "product", "wow").entrySet()) {
            var value = payload();
            CaptureJson.object(value.get("client")).put(invalid.getKey(), invalid.getValue());
            assertThrows(ResponseStatusException.class, () -> json.evidence(value), invalid.getKey());
        }
        for (String build : new String[] {"", "000", "12345678901"}) {
            var value = payload();
            CaptureJson.object(value.get("client")).put("gameBuild", build);
            assertThrows(ResponseStatusException.class, () -> json.evidence(value));
        }
    }

    @Test void patch_tolerance_does_not_relax_map_or_result_validation() throws Exception {
        var wrongMap = payload();
        CaptureJson.object(CaptureJson.object(wrongMap.get("capture")).get("map")).put("gameMapId", 489);
        assertThrows(ResponseStatusException.class, () -> json.evidence(wrongMap));
        var wrongWinner = payload();
        CaptureJson.object(CaptureJson.object(wrongWinner.get("capture")).get("outcome")).put("winningObservedTeamKey", "7");
        assertThrows(ResponseStatusException.class, () -> json.evidence(wrongWinner));
        var wrongSchema = payload();
        wrongSchema.put("schemaVersion", 99);
        assertThrows(ResponseStatusException.class, () -> json.evidence(wrongSchema));
    }
    @Test void anniversary_profile_preserves_provenance_and_rejects_cross_client_maps() throws Exception {
        var value = payload();
        var client = CaptureJson.object(value.get("client"));
        client.putAll(Map.of("product", "wow_anniversary", "adapterVersion", "tbc-anniversary-wsg-test-v1",
                "gameVersion", "2.5.6", "gameBuild", "70006", "interface", 20506));
        var map = CaptureJson.object(CaptureJson.object(value.get("capture")).get("map"));
        map.putAll(Map.of("profile", "TBC_ANNIVERSARY_WSG_TEST", "gameMapId", 489));
        byte[] before = json.canonical(value);
        assertDoesNotThrow(() -> json.evidence(value));
        assertArrayEquals(before, json.canonical(value));
        map.put("gameMapId", 2997);
        assertThrows(ResponseStatusException.class, () -> json.evidence(value));
        map.put("gameMapId", 489);
        map.put("profile", "DARKSPEAR_DIAGNOSTIC");
        assertThrows(ResponseStatusException.class, () -> json.evidence(value));
    }

    @Test void temporary_profile_cannot_become_a_matchmade_or_full_roster_match() {
        var now = java.time.Instant.now();
        for (var mode : io.rbgs.api.matches.MatchMode.values()) {
            for (var policy : io.rbgs.api.matches.MatchEvidencePolicy.values()) {
                boolean allowed = mode == io.rbgs.api.matches.MatchMode.CONTROLLED_TEST
                        && policy == io.rbgs.api.matches.MatchEvidencePolicy.CAPTURE_ONLY;
                org.junit.jupiter.api.function.Executable create = () -> new io.rbgs.api.matches.MatchEntity(
                        java.util.UUID.randomUUID(), now, now, "[]", mode, policy, "TBC_ANNIVERSARY_WSG_TEST", now);
                if (allowed) assertDoesNotThrow(create);
                else assertThrows(IllegalArgumentException.class, create);
            }
        }
    }

    @Test void resumed_capture_is_retained_but_cannot_satisfy_full_roster_verification() throws Exception {
        var report = payload();
        var capture = CaptureJson.object(report.get("capture"));
        capture.put("continuity", "RESUMED");
        capture.put("interruptions", java.util.List.of(Map.of("before", 1791734400L, "after", 1791734410L)));
        report.remove("assignmentCode");
        report.put("assignmentCodeSha256", "fixture-hash");
        var assignment = org.mockito.Mockito.mock(io.rbgs.api.matches.MatchAssignmentEntity.class);
        org.mockito.Mockito.when(assignment.getId()).thenReturn(CaptureJson.uuid(report.get("assignmentId")));
        org.mockito.Mockito.when(assignment.getCaptureId()).thenReturn(CaptureJson.uuid(report.get("captureId")));
        org.mockito.Mockito.when(assignment.getCodeHash()).thenReturn("fixture-hash");
        var match = org.mockito.Mockito.mock(io.rbgs.api.matches.MatchEntity.class);
        org.mockito.Mockito.when(match.getId()).thenReturn(CaptureJson.uuid(report.get("matchId")));
        org.mockito.Mockito.when(match.getEvidencePolicy()).thenReturn(io.rbgs.api.matches.MatchEvidencePolicy.FULL_ROSTER);
        org.mockito.Mockito.when(match.getProfile()).thenReturn("DARKSPEAR_DIAGNOSTIC");
        byte[] stored = json.canonical(report);
        var evidence = new CaptureEvidenceEntity(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), assignment,
                ((Number)report.get("captureRevision")).intValue(), java.time.Instant.now(), CaptureJson.sha(stored),
                CaptureJson.sha(stored), new String(stored, java.nio.charset.StandardCharsets.UTF_8), null);
        var result = new io.rbgs.api.matches.MatchVerification(json).normalize(match, evidence);
        assertEquals("UNVERIFIABLE", result.status());
        assertEquals("CAPTURE_INTERRUPTED", result.reason());
        assertEquals(new String(stored, java.nio.charset.StandardCharsets.UTF_8), evidence.getRedactedPayload());
    }

    @Test void resumed_final_preserves_interruption_and_requires_bounded_ordered_gaps() throws Exception {
        var value = payload();
        var capture = CaptureJson.object(value.get("capture"));
        capture.put("continuity", "RESUMED");
        assertThrows(ResponseStatusException.class, () -> json.evidence(value));
        capture.put("interruptions", java.util.List.of(Map.of("before", 1791734400L, "after", 1791734410L)));
        byte[] before = json.canonical(value);
        assertDoesNotThrow(() -> json.evidence(value));
        assertArrayEquals(before, json.canonical(value));
        for (Object gaps : java.util.List.of(java.util.List.of(),
                java.util.List.of(Map.of("before", 100, "after", 99)),
                java.util.List.of(Map.of("before", 100, "after", 221)),
                java.util.List.of(Map.of("before", 100, "after", 110), Map.of("before", 105, "after", 115)),
                java.util.Collections.nCopies(33, Map.of("before", 100, "after", 110)))) {
            capture.put("interruptions", gaps);
            assertThrows(ResponseStatusException.class, () -> json.evidence(value));
        }
        capture.put("interruptions", java.util.List.of(Map.of("before", 100, "after", 110)));
        capture.put("continuity", "CONTINUOUS");
        assertThrows(ResponseStatusException.class, () -> json.evidence(value));
    }

    @Test void optional_flag_statistics_preserve_zero_and_absence_and_reject_invalid_numbers() throws Exception {
        var value = payload();
        var row = CaptureJson.object(((java.util.List<?>)CaptureJson.object(value.get("capture")).get("roster")).getFirst());
        row.put("flagsCaptured", 0);
        row.put("flagsReturned", 3);
        byte[] before = json.canonical(value);
        assertDoesNotThrow(() -> json.evidence(value));
        assertArrayEquals(before, json.canonical(value));
        for(String key : java.util.List.of("flagsCaptured", "flagsReturned")) {
            for(Object bad : java.util.List.of(-1, 9007199254740992L, "3", 1.5, true)) {
                row.put(key, bad);
                assertThrows(ResponseStatusException.class, () -> json.evidence(value));
            }
            row.put(key, null);
            assertDoesNotThrow(() -> json.evidence(value));
            row.remove(key);
            assertDoesNotThrow(() -> json.evidence(value));
        }
    }

}
