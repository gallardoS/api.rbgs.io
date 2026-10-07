package io.rbgs.api.emailnotifications.persistence;



import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "email_campaigns", schema = "rbgs")
@Getter
@NoArgsConstructor
public class EmailCampaignEntity {
    @Id @Column(length = 32) private String id;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
    public EmailCampaignEntity(String id) { this.id = id; }
}
