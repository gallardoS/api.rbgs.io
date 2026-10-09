package io.rbgs.api.characters.selection;
import java.util.UUID;
import org.springframework.security.core.Authentication;
/** Success requires authenticated Forever ownership evidence, never a public name lookup. */
public interface ForeverOwnershipVerifier {
    void requireOwnership(UUID accountId, CharacterIdentity character, Authentication authentication);
}
