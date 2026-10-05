package io.rbgs.api.identity;

import io.rbgs.api.identity.dto.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import io.rbgs.api.identity.entity.AccountEntity;

@Service
public class AccountService {
    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account upsert(String issuer, String subject, String displayName) {
        accounts.upsert(UUID.randomUUID(), issuer, subject, displayName);
        return findByIdentity(issuer, subject);
    }

    @Transactional(readOnly = true)
    public Account findByIdentity(String issuer, String subject) {
        AccountEntity entity = accounts.findByProviderIssuerAndProviderSubject(issuer, subject).orElseThrow();
        return new Account(entity.getId(), entity.getDisplayName(), entity.getRegion(), entity.getStatus(), entity.getRole());
    }

    @Transactional(readOnly = true)
    public Profile currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return null;
        }
        Account account = findByIdentity(user.getIdToken().getIssuer().toString(), user.getSubject());
        return new Profile(account.id(), account.displayName(), account.region(), account.role());
    }
}
