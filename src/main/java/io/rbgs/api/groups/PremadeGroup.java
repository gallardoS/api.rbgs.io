package io.rbgs.api.groups;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "premade_groups", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PremadeGroup {
    @Id private UUID id;
    @Column(name = "captain_id", nullable = false) private UUID captainId;
    @Column(nullable = false) private long revision;
    @Column(nullable = false, updatable = false, length = 2) private String region;
    @Column(name = "join_code", length = 8) private String joinCode;
    @Column(name = "join_code_hash", length = 64) private String joinCodeHash;

    public PremadeGroup(UUID captainId, String region) { this.id = UUID.randomUUID(); this.captainId = captainId; this.region = region; }
    public void rosterChanged() { revision++; }
    public void transfer(UUID captainId) { this.captainId = captainId; rosterChanged(); }
    public void code(String code, String hash) { this.joinCode = code; this.joinCodeHash = hash; }
}
