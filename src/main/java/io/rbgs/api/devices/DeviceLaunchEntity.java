package io.rbgs.api.devices;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "device_launches", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class DeviceLaunchEntity {
    @Id private UUID id;
    @Column(name="account_id", nullable=false) private UUID accountId;
    @Column(name="secret_hash", nullable=false, length=64) private String secretHash;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="expires_at", nullable=false) private Instant expiresAt;
    @Column(name="claim_hash", length=64) private String claimHash;
    @Column(name="device_id") private UUID deviceId;
    @Column(name="issued_credential", nullable=false) private boolean issuedCredential;

    DeviceLaunchEntity(UUID account, String hash, Instant now) {
        id=UUID.randomUUID(); accountId=account; secretHash=hash; createdAt=now; expiresAt=now.plusSeconds(120);
    }
    void claim(String hash, UUID device, boolean issued) {
        claimHash=hash; deviceId=device; issuedCredential=issued;
    }
}
