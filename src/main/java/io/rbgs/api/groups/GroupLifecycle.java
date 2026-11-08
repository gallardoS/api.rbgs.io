package io.rbgs.api.groups;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class GroupLifecycle {
    private final JdbcTemplate jdbc;
    private final GroupService groups;

    void browserSeen(UUID account) {
        jdbc.update("UPDATE rbgs.group_members SET browser_seen_at=clock_timestamp() WHERE account_id=?", account);
    }

    @Scheduled(initialDelayString = "${rbgs.groups.lifecycle.startup-grace-ms:30000}", fixedDelay = 2000)
    void sweep() {
        var now = Instant.now();
        for (UUID id : groups.expirationCandidates(now)) {
            try {
                groups.expire(id, now);
            } catch (RuntimeException failure) {
                log.warn("Could not expire lobby {}", id, failure);
            }
        }
    }
}
