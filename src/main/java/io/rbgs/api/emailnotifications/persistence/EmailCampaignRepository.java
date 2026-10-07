package io.rbgs.api.emailnotifications.persistence;



import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailCampaignRepository extends JpaRepository<EmailCampaignEntity, String> {
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(7251902)", nativeQuery = true)
    int lockLaunch();
}
