package io.rbgs.api.devices;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "device_pairings", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PairingEntity {
    @Id private UUID id;
    @Column(name = "code_hash", nullable = false, length = 64) private String codeHash;
    @Column(name = "polling_hash", nullable = false, length = 64) private String pollingHash;
    @Column(nullable = false, length = 80) private String name;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "account_id") private UUID accountId;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Column(name = "requested_scope", nullable = false, length = 128) private String requestedScope;

    PairingEntity(String codeHash, String pollingHash, String name, Instant now, String scope) {
        id = UUID.randomUUID(); this.codeHash = codeHash; this.pollingHash = pollingHash;
        this.name = name; expiresAt = now.plusSeconds(300);
        requestedScope = scope;
    }
    void approve(UUID accountId) { this.accountId = accountId; }
    void consume(Instant now) { consumedAt = now; }




}
