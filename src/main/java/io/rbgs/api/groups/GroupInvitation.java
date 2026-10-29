package io.rbgs.api.groups;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "group_invitations", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupInvitation {
    @Id private UUID id;
    @Column(name = "group_id", nullable = false) private UUID groupId;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    public GroupInvitation(UUID groupId, UUID accountId) {
        id = UUID.randomUUID(); this.groupId = groupId; this.accountId = accountId;
        expiresAt = Instant.now().plusSeconds(1800);
    }
    public void renew() { expiresAt = Instant.now().plusSeconds(1800); }
}
