package io.rbgs.api.characters;

import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AvatarPreferenceService {
    private final AccountService accounts;
    private final AccountRepository repository;
    private final JdbcTemplate db;
    public record Request(@NotNull @Pattern(regexp = "sd|hd") String style) { }
    public record Preference(String style, boolean configured) { }

    public Preference current(Authentication auth) { return find(actor(auth)); }
    public Preference find(UUID account) {
        return db.queryForObject("SELECT avatar_style FROM rbgs.accounts WHERE id=?", (row, index) -> {
            String style = row.getString("avatar_style");
            return new Preference(style == null ? "sd" : style, style != null);
        }, account);
    }
    @Transactional
    public Preference save(Authentication auth, Request request) {
        UUID actor = actor(auth);
        var account = repository.findForSelection(actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!"ACTIVE".equals(account.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        db.update("UPDATE rbgs.accounts SET avatar_style=? WHERE id=? AND avatar_style IS DISTINCT FROM ?",
                request.style(), actor, request.style());
        return new Preference(request.style(), true);
    }
    private UUID actor(Authentication auth) {
        var profile = accounts.currentUser(auth);
        if (profile == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return profile.id();
    }
}
