package io.rbgs.api.devices;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "devices", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceEntity {
    @Id private UUID id;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(nullable = false, length = 80) private String name;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "granted_scope", nullable = false, length = 128) private String grantedScope;

    DeviceEntity(UUID accountId, String name, String tokenHash, Instant now, String scope) {
        this.id = UUID.randomUUID(); this.accountId = accountId; this.name = name;
        this.tokenHash = tokenHash; this.createdAt = now; this.expiresAt = now.plusSeconds(90L * 86400);
        this.grantedScope = scope;
    }
    void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }


}
