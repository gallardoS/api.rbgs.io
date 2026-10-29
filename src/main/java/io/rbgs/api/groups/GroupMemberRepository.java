package io.rbgs.api.groups;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
        INSERT INTO rbgs.group_join_limits(account_id, window_start, attempts)
        VALUES (:account, CURRENT_TIMESTAMP, 1)
        ON CONFLICT (account_id) DO UPDATE SET
          attempts = CASE WHEN rbgs.group_join_limits.window_start <= CURRENT_TIMESTAMP - INTERVAL '1 minute'
            THEN 1 ELSE rbgs.group_join_limits.attempts + 1 END,
          window_start = CASE WHEN rbgs.group_join_limits.window_start <= CURRENT_TIMESTAMP - INTERVAL '1 minute'
            THEN CURRENT_TIMESTAMP ELSE rbgs.group_join_limits.window_start END
        WHERE rbgs.group_join_limits.attempts < 10
          OR rbgs.group_join_limits.window_start <= CURRENT_TIMESTAMP - INTERVAL '1 minute'
        """, nativeQuery = true)
    int recordJoinAttempt(UUID account);
    List<GroupMember> findByGroupIdOrderByAccountId(UUID groupId);
    Optional<GroupMember> findFirstByGroupIdAndAccountIdNotOrderByJoinedOrderAsc(UUID groupId, UUID accountId);
    @org.springframework.data.jpa.repository.Query("select m.groupId from GroupMember m where m.accountId = :accountId")
    Optional<UUID> findGroupIdByAccountId(UUID accountId);
    @org.springframework.data.jpa.repository.Query(value = "select nextval(pg_get_serial_sequence('rbgs.group_members', 'joined_order'))", nativeQuery = true)
    long nextMembershipOrder();
    long countByGroupId(UUID groupId);
}
