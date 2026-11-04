package io.rbgs.api.matchmaking;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name="soloq_lab_state",schema="rbgs")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
class SoloQueueLabState {
    @Id private int id;
    @Column(name="season_id",nullable=false) private UUID seasonId;
    @Column(nullable=false) private UUID generation;
    @Column(nullable=false,length=32) private String scenario;
    @Version private long version;
    SoloQueueLabState(UUID season,String scenario) { id=1; this.seasonId=season; this.scenario=scenario; generation=UUID.randomUUID(); }
    void reset(UUID season,String scenario) { seasonId=season; this.scenario=scenario; generation=UUID.randomUUID(); }
}
