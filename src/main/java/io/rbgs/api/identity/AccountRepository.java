package io.rbgs.api.identity;

import java.util.Optional;
import java.util.UUID;
import io.rbgs.api.identity.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {
    java.util.List<AccountEntity> findByDisplayNameAndStatus(String displayName, String status);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findForSelection(@Param("id") UUID id);

    // Match Java UUID.compareTo, including the signed halves used by other account-locking callers.
    @Query(value = """
            SELECT * FROM rbgs.accounts WHERE id IN (:ids)
            ORDER BY get_byte(uuid_send(id), 0) < 128, substring(uuid_send(id), 1, 8),
                     get_byte(uuid_send(id), 8) < 128, id
            FOR UPDATE
            """, nativeQuery = true)
    java.util.List<AccountEntity> lockQueueAccounts(@Param("ids") java.util.Collection<UUID> ids);

    Optional<AccountEntity> findByProviderIssuerAndProviderSubject(String issuer, String subject);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO rbgs.accounts (id, provider_issuer, provider_subject, display_name, region)
            VALUES (:id, :issuer, :subject, :displayName, 'EU')
            ON CONFLICT (provider_issuer, provider_subject) DO UPDATE
                SET display_name = EXCLUDED.display_name, updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("issuer") String issuer,
            @Param("subject") String subject, @Param("displayName") String displayName);
}
