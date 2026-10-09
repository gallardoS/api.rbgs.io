package io.rbgs.api.identity;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Reads account authorization from persistence instead of the login-time authorities. */
public final class AccountAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final ObjectProvider<AccountService> accounts;
    private final String requiredRole;
    private final boolean allowAnonymous;

    public AccountAuthorizationManager(ObjectProvider<AccountService> accounts, String requiredRole,
            boolean allowAnonymous) {
        this.accounts = accounts;
        this.requiredRole = requiredRole;
        this.allowAnonymous = allowAnonymous;
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated()
                || current instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return new AuthorizationDecision(allowAnonymous);
        }
        if (!(current.getPrincipal() instanceof OidcUser user)
                || user.getIdToken().getIssuer() == null || user.getSubject() == null) {
            return new AuthorizationDecision(false);
        }
        AccountService service = accounts.getIfAvailable();
        if (service == null) {
            return new AuthorizationDecision(false);
        }
        try {
            Account account = service.findByIdentity(user.getIdToken().getIssuer().toString(), user.getSubject());
            return new AuthorizationDecision(account != null && "ACTIVE".equals(account.status())
                    && (requiredRole == null || requiredRole.equals(account.role())));
        } catch (NoSuchElementException missingAccount) {
            return new AuthorizationDecision(false);
        }
    }
}
