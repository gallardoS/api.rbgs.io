package io.rbgs.api.characters.selection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CharacterIdentityRepository extends JpaRepository<CharacterIdentity, UUID> { }
