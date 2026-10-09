package io.rbgs.api.characters.selection;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
@Component
public class UnavailableForeverOwnershipVerifier implements ForeverOwnershipVerifier {
    @Override
    public void requireOwnership(UUID accountId, CharacterIdentity character, Authentication authentication) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Forever ownership verification is not implemented");
    }
}
