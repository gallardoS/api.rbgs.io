package io.rbgs.api.emailnotifications.persistence;

import io.rbgs.api.emailnotifications.feedback.EmailSuppressionReason;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "email_suppressions", schema = "rbgs")
@Getter
@Setter
@NoArgsConstructor
public class EmailSuppressionEntity {
    @Id @Column(length = 254) private String email;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private EmailSuppressionReason reason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
