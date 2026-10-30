package io.rbgs.api.characters;

import io.rbgs.api.characters.dto.WowAccountProfile.WowCharacter;
import tools.jackson.databind.ObjectMapper;
import io.rbgs.api.groups.GroupMemberRepository;
import io.rbgs.api.groups.PremadeGroupRepository;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** A verified Classic picker preference, independent of season/device readiness and Forever ownership. */
@Service
@RequiredArgsConstructor
public class PlayCharacterService {
    private final WowCharacterService characters;
    private final ObjectMapper json;
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final GroupMemberRepository members;
    private final PremadeGroupRepository groups;
    private final JdbcTemplate db;
    private final PlatformTransactionManager transactions;

    public record Reference(@NotBlank @Pattern(regexp = "profile-classic(1x|ann)?-(eu|us)") String namespace,
            @Positive long wowAccountId, @Positive long realmId, @Positive long characterId) { }
    public record Selected(String namespace, long wowAccountId, long realmId, long characterId,
            String region, String name, String realm, WowCharacter character) { }

    public Selected current(Authentication auth) { return find(actor(auth)); }

    public Selected find(UUID account) {
        return db.query("SELECT * FROM rbgs.play_characters WHERE account_id=?", (row, index) -> new Selected(
                row.getString("namespace"), row.getLong("wow_account_id"), row.getLong("realm_id"),
                row.getLong("character_id"), row.getString("region"), row.getString("name"), row.getString("realm"),
                row.getString("character_profile") == null ? null : json.readValue(row.getString("character_profile"), WowCharacter.class)),
                account).stream().findFirst().orElse(null);
    }

    public Selected save(Authentication auth, OAuth2AuthorizedClient client, Reference reference) {
        UUID actor = actor(auth);
        // Provider work precedes the database transaction and uses the existing bounded per-authorization cache.
        var matches = characters.currentCharacters(client).wowAccounts().stream()
                .filter(account -> account.id() == reference.wowAccountId())
                .flatMap(account -> account.characters().stream())
                .filter(c -> Objects.equals(c.namespace(), reference.namespace()) && c.id() == reference.characterId()
                        && c.realm().id() == reference.realmId() && c.level() >= 60).toList();
        if (matches.size() != 1) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose an available character from your Battle.net account");
        var character = matches.getFirst();
        String region = character.namespace().endsWith("-us") ? "NA" : "EU";
        var selected = new Selected(character.namespace(), reference.wowAccountId(), character.realm().id(), character.id(),
                region, character.name(), character.realm().name() == null || character.realm().name().isBlank()
                    ? character.realm().slug() : character.realm().name(), character);
        return new TransactionTemplate(transactions).execute(status -> {
            var account = accountRepository.findForSelection(actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
            if (!"ACTIVE".equals(account.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var membership = members.findById(actor).orElse(null);
            if (membership != null && !region.equals(groups.findById(membership.getGroupId()).orElseThrow().getRegion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a character from your lobby's region");
            if (!selected.equals(find(actor))) db.update("""
                    INSERT INTO rbgs.play_characters(account_id,namespace,wow_account_id,realm_id,character_id,region,name,realm,character_profile)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (account_id) DO UPDATE SET
                    namespace=EXCLUDED.namespace, wow_account_id=EXCLUDED.wow_account_id, realm_id=EXCLUDED.realm_id,
                    character_id=EXCLUDED.character_id, region=EXCLUDED.region, name=EXCLUDED.name, realm=EXCLUDED.realm, character_profile=EXCLUDED.character_profile
                    """, actor, selected.namespace(), selected.wowAccountId(), selected.realmId(), selected.characterId(),
                    region, selected.name(), selected.realm(), json.writeValueAsString(character));
            return selected;
        });
    }

    private UUID actor(Authentication auth) {
        var profile = accounts.currentUser(auth);
        if (profile == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return profile.id();
    }
}
