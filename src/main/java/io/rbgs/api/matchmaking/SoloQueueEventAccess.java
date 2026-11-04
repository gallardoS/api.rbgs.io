package io.rbgs.api.matchmaking;

import io.rbgs.api.identity.AccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
class SoloQueueEventAccess {
    private final AccountRepository accounts;
    private final SessionRepository<? extends Session> sessions;
    boolean valid(UUID account,String id) {
        var session=sessions.findById(id);if(session==null||session.isExpired())return false;
        Object saved=session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if(!(saved instanceof SecurityContext context)||context.getAuthentication()==null
                ||!(context.getAuthentication().getPrincipal() instanceof OidcUser user))return false;
        return accounts.findByProviderIssuerAndProviderSubject(user.getIdToken().getIssuer().toString(),user.getSubject())
                .filter(a->a.getId().equals(account)&&a.getStatus().equals("ACTIVE")).isPresent();
    }
}
