package io.rbgs.api.matches;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "match_disputes", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchDispute {
    @Id private UUID id;
    @Column(name = "match_id", nullable = false, updatable = false) private UUID matchId;
    @Column(name = "account_id", nullable = false, updatable = false) private UUID accountId;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "opened_at", nullable = false, updatable = false) private Instant openedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "opened_for_version", nullable = false) private long openedForVersion;
    @Column(name = "last_moderator_at") private Instant lastModeratorAt;
    @Column(name = "seen_at") private Instant seenAt;
    @Version private long version;

    public MatchDispute(UUID match, UUID account, long resultVersion, Instant at) {
        id = UUID.randomUUID(); matchId = match; accountId = account; openedAt = at;
        reopen(resultVersion, at);
    }
    public void reopen(long resultVersion, Instant at) { status = "OPEN"; openedForVersion = resultVersion; updatedAt = at; }
    public void playerReply(Instant at) { status = "OPEN"; updatedAt = at; }
    public void requestEvidence(Instant at) { status = "WAITING_PLAYER"; updatedAt = at; lastModeratorAt = at; }
    public void resolve(Instant at) { status = "RESOLVED"; updatedAt = at; lastModeratorAt = at; }
    public void seen(Instant at) { seenAt = at; }
    public boolean unread() { return lastModeratorAt != null && (seenAt == null || lastModeratorAt.isAfter(seenAt)); }
}
