package io.rbgs.api.groups;

import jakarta.persistence.*;
import java.util.UUID;
import io.rbgs.api.characters.selection.MatchRole;
import lombok.*;

@Entity
@Table(name = "group_members", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMember {
    @Id @Column(name = "account_id") private UUID accountId;
    @Column(name = "group_id", nullable = false) private UUID groupId;
    @Column(name = "joined_order", nullable = false, insertable = false) private Long joinedOrder;
    @Enumerated(EnumType.STRING) @Column(name = "display_role") private MatchRole displayRole;
    @Column(name = "display_role_version", nullable = false) private long displayRoleVersion;
    @Column(name = "ready_revision") private Long readyRevision;
    @Column(name = "ready_season_id") private UUID readySeasonId;
    @Column(name = "ready_season_version") private Long readySeasonVersion;
    @Column(name = "ready_selection_version") private Long readySelectionVersion;

    public GroupMember(UUID accountId, UUID groupId) { this.accountId = accountId; this.groupId = groupId; }
    public void moveTo(UUID groupId, long joinedOrder) {
        this.groupId = groupId; this.joinedOrder = joinedOrder;
        displayRole = null; displayRoleVersion = 0; unready();
    }
    public void displayRole(MatchRole role) {
        if (displayRole != role) { displayRole = role; displayRoleVersion++; }
    }
    public void confirm(long revision, UUID seasonId, long seasonVersion, long selectionVersion) {
        readyRevision = revision; readySeasonId = seasonId;
        readySeasonVersion = seasonVersion; readySelectionVersion = selectionVersion;
    }
    public void unready() { readyRevision = null; readySeasonId = null; readySeasonVersion = null; readySelectionVersion = null; }
}
