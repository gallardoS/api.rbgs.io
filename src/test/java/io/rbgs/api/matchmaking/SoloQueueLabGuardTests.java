package io.rbgs.api.matchmaking;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoloQueueLabGuardTests {
    @Test void onlyDedicatedDatabasesAndLoopbackAreAllowed() {
        assertDoesNotThrow(()->SoloQueueLabGuard.validate("rbgs_soloq_lab","127.0.0.1"));
        assertDoesNotThrow(()->SoloQueueLabGuard.validate("rbgs_soloq_lab_tests","127.0.0.1"));
        assertThrows(IllegalStateException.class,()->SoloQueueLabGuard.validate("rbgs","127.0.0.1"));
        assertThrows(IllegalStateException.class,()->SoloQueueLabGuard.validate("rbgs_soloq_lab","0.0.0.0"));
    }
}
