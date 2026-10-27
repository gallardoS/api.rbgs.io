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

}
