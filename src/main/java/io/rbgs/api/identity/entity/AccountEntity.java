package io.rbgs.api.identity.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts", schema = "rbgs")
public class AccountEntity {
    @Id
    private UUID id;
    @Column(name = "provider_issuer", nullable = false, length = 255)
    private String providerIssuer;
    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;
    @Column(nullable = false, length = 16)
    private String region;
    @Column(name = "account_status", nullable = false, length = 16)
    private String status;
    @Column(name = "account_role", nullable = false, length = 16)
    private String role;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected AccountEntity() { }

    public UUID getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getRegion() { return region; }
    public String getStatus() { return status; }
    public String getRole() { return role; }
}
