package io.rbgs.api.evidence;

import java.time.Duration;
import java.util.*;

public enum CaptureProfile {
    DARKSPEAR_DIAGNOSTIC(2997, "wow_classic_beta", Set.of("darkspear-scoreboard-v1", "darkspear-70338-test-1"),
            Duration.ofMinutes(2), Duration.ofHours(4)),
    TBC_ANNIVERSARY_WSG_TEST(489, "wow_anniversary", Set.of("tbc-anniversary-wsg-test-v1"),
            Duration.ofMinutes(2), Duration.ofHours(4));

    private final long mapId;
    private final String product;
    private final Set<String> adapters;
    private final Duration clockTolerance;
    private final Duration maxDuration;

    CaptureProfile(long mapId, String product, Set<String> adapters, Duration clockTolerance, Duration maxDuration) {
        this.mapId = mapId;
        this.product = product;
        this.adapters = adapters;
        this.clockTolerance = clockTolerance;
        this.maxDuration = maxDuration;
    }

    public long mapId() { return mapId; }
    public Duration clockTolerance() { return clockTolerance; }
    public Duration maxDuration() { return maxDuration; }
    public boolean accepts(Map<String, Object> client) {
        return product.equals(client.get("product")) && adapters.contains(client.get("adapterVersion"));
    }
    public static Optional<CaptureProfile> find(String profile) {
        return Arrays.stream(values()).filter(p -> p.name().equals(profile)).findFirst();
    }
    public static boolean supports(Map<String, Object> client) {
        return Arrays.stream(values()).anyMatch(p -> p.accepts(client));
    }
}
