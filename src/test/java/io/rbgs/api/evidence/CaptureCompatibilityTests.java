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
}
