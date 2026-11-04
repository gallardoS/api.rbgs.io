package io.rbgs.api.matchmaking;

import org.springframework.data.jpa.repository.*;

interface SoloQueueLabRepository extends JpaRepository<SoloQueueLabState,Integer> {
    @Query(value="SELECT current_database()",nativeQuery=true) String databaseName();
    @Query(value="SELECT a.id,a.display_name,s.match_role,s.captain_consent FROM rbgs.accounts a "
            +"JOIN rbgs.season_selections s ON s.account_id=a.id WHERE a.provider_issuer='https://soloq-lab.invalid' "
            +"AND s.season_id=:season ORDER BY CAST(a.provider_subject AS INTEGER)",nativeQuery=true)
    java.util.List<Object[]> players(java.util.UUID season);
}
