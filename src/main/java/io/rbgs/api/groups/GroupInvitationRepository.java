package io.rbgs.api.groups;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupInvitationRepository extends JpaRepository<GroupInvitation, UUID> {
    List<GroupInvitation> findByAccountIdAndExpiresAtAfterOrderByExpiresAt(UUID accountId, Instant now);
    List<GroupInvitation> findByGroupIdAndExpiresAtAfterOrderByExpiresAt(UUID groupId, Instant now);
    Optional<GroupInvitation> findByGroupIdAndAccountId(UUID groupId, UUID accountId);
    Optional<GroupInvitation> findByIdAndGroupId(UUID id, UUID groupId);
    long countByGroupIdAndExpiresAtAfter(UUID groupId, Instant now);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from GroupInvitation i where i.accountId = :accountId")
    void deleteByAccountId(UUID accountId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from GroupInvitation i where i.id = :id and i.groupId = :groupId")
    void deleteInvitation(UUID id, UUID groupId);
}
