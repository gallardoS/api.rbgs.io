package io.rbgs.api.groups;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GroupJoinLimiter {
    private final GroupMemberRepository members;

    // Failed lookups must consume the limit even when the joining transaction rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(UUID accountId) {
        if (members.recordJoinAttempt(accountId) == 0)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many lobby code attempts. Wait one minute and try again");
    }
}
