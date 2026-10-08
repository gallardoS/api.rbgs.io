package io.rbgs.api.emailnotifications.persistence;

import io.rbgs.api.emailnotifications.delivery.EmailKind;
import io.rbgs.api.emailnotifications.delivery.EmailStatus;

import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface EmailOutboxRepository extends JpaRepository<EmailOutboxEntity, UUID> {
    boolean existsBySubscriptionIdAndKind(UUID subscriptionId, EmailKind kind);
    boolean existsByIdAndStatus(UUID id, EmailStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailOutboxEntity o set o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.CANCELLED, o.html = null, o.plainText = null where o.subscription.id = :id and o.status in (io.rbgs.api.emailnotifications.delivery.EmailStatus.PENDING, io.rbgs.api.emailnotifications.delivery.EmailStatus.INFLIGHT) and (:kind is null or o.kind = :kind)")
    int cancel(@Param("id") UUID id, @Param("kind") EmailKind kind);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update EmailOutboxEntity o set o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.CANCELLED, o.html = null, o.plainText = null
            where o.status in (io.rbgs.api.emailnotifications.delivery.EmailStatus.PENDING, io.rbgs.api.emailnotifications.delivery.EmailStatus.INFLIGHT)
            and exists (select s.email from EmailSuppressionEntity s where s.email = o.subscription.email)
            """)
    int cancelSuppressed();

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(7251901)", nativeQuery = true)
    int lockReservations();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update EmailOutboxEntity o set o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.CANCELLED, o.html = null, o.plainText = null
            where o.status in (io.rbgs.api.emailnotifications.delivery.EmailStatus.PENDING, io.rbgs.api.emailnotifications.delivery.EmailStatus.INFLIGHT)
            and (o.subscription.unsubscribedAt is not null or (o.kind = io.rbgs.api.emailnotifications.delivery.EmailKind.CONFIRMATION
                and (o.subscription.confirmedAt is not null or o.subscription.confirmationExpiresAt < :now)))
            """)
    int cancelInvalid(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailOutboxEntity o set o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.REVIEW where o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.INFLIGHT and o.nextAttemptAt <= :now")
    int reviewExpiredLeases(@Param("now") Instant now);

    long countByFirstAttemptAtAfter(Instant cutoff);

    @Query(value = """
                SELECT o.* FROM rbgs.email_outbox o
                JOIN rbgs.email_subscriptions s ON s.id = o.subscription_id
                WHERE o.status = 'PENDING' AND o.next_attempt_at <= CURRENT_TIMESTAMP
                AND NOT EXISTS (SELECT 1 FROM rbgs.email_suppressions e WHERE e.email = s.email)
                AND (:quota = false OR o.first_attempt_at IS NOT NULL)
                AND (o.kind = 'CONFIRMATION' OR (s.confirmed_at IS NOT NULL AND :live = true))
                ORDER BY CASE WHEN o.kind = 'CONFIRMATION' THEN 0 ELSE 1 END, o.created_at
                LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, nativeQuery = true)
    List<EmailOutboxEntity> findNext(@Param("quota") boolean quota, @Param("live") boolean live);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailOutboxEntity o set o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.SENT, o.sentAt = :now, o.providerId = :provider, o.html = null, o.plainText = null where o.id = :id and o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.INFLIGHT")
    int markSent(@Param("provider") String provider, @Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailOutboxEntity o set o.status = :status, o.nextAttemptAt = :nextAttempt where o.id = :id and o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.INFLIGHT")
    int markFailed(@Param("status") EmailStatus status, @Param("nextAttempt") Instant nextAttempt, @Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailOutboxEntity o set o.html = null, o.plainText = null where o.status = io.rbgs.api.emailnotifications.delivery.EmailStatus.REVIEW and o.firstAttemptAt < :cutoff")
    int scrubReviewPayloads(@Param("cutoff") Instant cutoff);
}
