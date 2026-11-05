package io.rbgs.api.matchmaking;

import java.util.*;
import org.springframework.data.jpa.repository.*;

interface PremadeQueueLabRepository extends JpaRepository<PremadeQueueLabState,Integer> {
    @Query(value="SELECT a.id,a.display_name,m.group_id,(g.captain_id=a.id) FROM rbgs.accounts a "
            +"JOIN rbgs.group_members m ON m.account_id=a.id JOIN rbgs.premade_groups g ON g.id=m.group_id "
            +"WHERE a.provider_issuer='https://premade-lab.invalid' ORDER BY CAST(a.provider_subject AS INTEGER)",nativeQuery=true)
    List<Object[]> players();
}
