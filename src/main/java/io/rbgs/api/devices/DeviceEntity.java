package io.rbgs.api.devices;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "devices", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceEntity {
    @Id private UUID id;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "removed_at") private Instant removedAt;
    @Column(name = "granted_scope", nullable = false, length = 128) private String grantedScope;
    @Column(name = "last_seen_at") private Instant lastSeenAt;

    DeviceEntity(UUID accountId, String tokenHash, Instant now, String scope) {
        this.id = UUID.randomUUID(); this.accountId = accountId;
        this.tokenHash = tokenHash; this.createdAt = now; this.expiresAt = now.plusSeconds(90L * 86400);
        this.grantedScope = scope;
    }
    void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
    void heartbeat(Instant now) { lastSeenAt = now; }
    void grantCapturePermissions() { grantedScope=DeviceService.CAPTURE_SCOPE; }
    void removeFromListing(Instant now) { if (removedAt == null) removedAt = now; }
}
