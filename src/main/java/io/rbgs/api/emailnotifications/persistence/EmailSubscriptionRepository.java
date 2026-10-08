package io.rbgs.api.emailnotifications.persistence;

import io.rbgs.api.emailnotifications.delivery.EmailKind;

import java.util.*;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EmailSubscriptionRepository extends JpaRepository<EmailSubscriptionEntity, UUID> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO rbgs.email_subscriptions (id, email, language, confirmation_hash, confirmation_expires_at)
            VALUES (:id, :email, :language, :hash, :expiresAt) ON CONFLICT (email) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("email") String email, @Param("language") String language,
            @Param("hash") String hash, @Param("expiresAt") Instant expiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailSubscriptionEntity> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailSubscriptionEntity> findByConfirmationHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailSubscriptionEntity> findByUnsubscribeHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from EmailSubscriptionEntity s where s.confirmedAt is not null and s.unsubscribedAt is null
            and not exists (select e.email from EmailSuppressionEntity e where e.email = s.email)
            and not exists (select o.id from EmailOutboxEntity o where o.subscription = s and o.kind = io.rbgs.api.emailnotifications.delivery.EmailKind.LAUNCH)
            """)
    List<EmailSubscriptionEntity> findLaunchRecipients();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailSubscriptionEntity s set s.unsubscribeHash = :hash where s.id = :id")
    int setUnsubscribeHash(@Param("id") UUID id, @Param("hash") String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from EmailSubscriptionEntity s where
            (s.confirmedAt is null and s.requestedAt < :pendingCutoff)
            or s.unsubscribedAt < :completedCutoff
            or exists (select o.id from EmailOutboxEntity o where o.subscription = s
                and o.kind = io.rbgs.api.emailnotifications.delivery.EmailKind.LAUNCH and o.status = 'SENT' and o.sentAt < :completedCutoff)
            """)
    int purgeExpired(@Param("pendingCutoff") Instant pendingCutoff, @Param("completedCutoff") Instant completedCutoff);
}
