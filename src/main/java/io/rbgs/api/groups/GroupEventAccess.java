package io.rbgs.api.groups;

import io.rbgs.api.identity.AccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.session.SessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GroupEventAccess {
    private final AccountRepository accounts;
    private final SessionRepository<? extends Session> sessions;

    boolean valid(UUID account, String sessionId) {
        var session = sessions.findById(sessionId);
        return session != null && !session.isExpired()
                && accounts.findById(account).filter(a -> "ACTIVE".equals(a.getStatus())).isPresent();
    }
}
