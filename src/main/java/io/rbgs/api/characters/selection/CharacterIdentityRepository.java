package io.rbgs.api.characters.selection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CharacterIdentityRepository extends JpaRepository<CharacterIdentity, UUID> {
    Optional<CharacterIdentity> findFirstByAccountIdAndSourceAndProductAndRegionAndNameAndRealmOrderById(
            UUID accountId, CharacterIdentity.Source source, String product, String region, String name, String realm);
}
