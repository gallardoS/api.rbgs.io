package io.rbgs.api.matchmaking;

import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.entity.AccountEntity;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SoloQueueEventAccessTests {
    @Test void streamRequiresPersistedIdentityAndStopsAfterAccountOrSessionRevocation() {
        var accounts=mock(AccountRepository.class);
        @SuppressWarnings("unchecked") SessionRepository<MapSession> sessions=mock(SessionRepository.class);
        var access=new SoloQueueEventAccess(accounts,sessions);var session=new MapSession();UUID id=UUID.randomUUID();
        when(sessions.findById(session.getId())).thenReturn(session);
        assertFalse(access.valid(id,session.getId()));
        var token=OidcIdToken.withTokenValue("fixture").issuer("https://stream-fixture.example").subject("player").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        var principal=new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),token);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        var account=mock(AccountEntity.class);when(account.getId()).thenReturn(id);when(account.getStatus()).thenReturn("ACTIVE");
        when(accounts.findByProviderIssuerAndProviderSubject("https://stream-fixture.example","player")).thenReturn(Optional.of(account));
        assertTrue(access.valid(id,session.getId()));assertFalse(access.valid(UUID.randomUUID(),session.getId()));
        when(account.getStatus()).thenReturn("SUSPENDED");assertFalse(access.valid(id,session.getId()));
        when(account.getStatus()).thenReturn("ACTIVE");when(sessions.findById(session.getId())).thenReturn(null);assertFalse(access.valid(id,session.getId()));
    }
}
