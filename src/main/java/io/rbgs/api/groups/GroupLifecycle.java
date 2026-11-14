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

    void invitationBrowserSeen(UUID account, String sessionId) {
        jdbc.update("""
                INSERT INTO rbgs.lobby_browser_presence (session_primary_id, account_id, seen_at)
                SELECT primary_id, ?, clock_timestamp() FROM rbgs.spring_session
                WHERE session_id = ? AND expiry_time > ?
                FOR KEY SHARE
                ON CONFLICT (session_primary_id) DO UPDATE SET seen_at = GREATEST(
                    rbgs.lobby_browser_presence.seen_at, EXCLUDED.seen_at)
                """, account, sessionId, Instant.now().toEpochMilli());
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
