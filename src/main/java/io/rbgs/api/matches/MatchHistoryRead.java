package io.rbgs.api.matches;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "match_history_reads", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchHistoryRead {
    @Id @Column(name = "assignment_id") private UUID assignmentId;
    @Column(name = "seen_version", nullable = false) private long seenVersion;
    public MatchHistoryRead(UUID assignment, long version) { assignmentId = assignment; seenVersion = version; }
}
