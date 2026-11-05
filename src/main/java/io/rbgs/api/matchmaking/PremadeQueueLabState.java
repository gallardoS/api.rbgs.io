package io.rbgs.api.matchmaking;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity @Table(name="premade_lab_state",schema="rbgs")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
class PremadeQueueLabState {
    @Id private int id;
    @Column(name="season_id",nullable=false) private UUID seasonId;
    @Column(nullable=false) private UUID generation;
    @Version private long version;
    PremadeQueueLabState(UUID season) { id=1;reset(season); }
    void reset(UUID season) { seasonId=season;generation=UUID.randomUUID(); }
}
