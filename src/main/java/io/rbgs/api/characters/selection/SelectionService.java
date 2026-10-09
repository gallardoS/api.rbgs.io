package io.rbgs.api.characters.selection;

import java.util.Objects;
import java.util.UUID;
import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.seasons.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SelectionService {
    private final SelectionSettings settings;
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final SeasonRepository seasons;
    private final CharacterIdentityRepository identities;
    private final SeasonSelectionRepository selections;
    private final ForeverOwnershipVerifier verifier;

    @Transactional(readOnly = true)
    public PlayContext context(Authentication authentication) {
        UUID accountId = accountId(authentication);
        UUID seasonId = settings.seasonId();
        if (seasonId == null) return new PlayContext(null, null, "UNVERIFIED", false);
        var season = seasons.findById(seasonId).orElseThrow(() -> unavailable("Configured season does not exist"));
        var selection = selections.findByAccountIdAndSeasonId(accountId, seasonId).orElse(null);
        return new PlayContext(new PlayContext.Season(season.getId(), season.getName(), season.getKind(),
                season.getRatingSubjectType(), season.getVersion()), selection == null ? null : view(selection, season),
                "UNVERIFIED", false);
    }

    @Transactional
    public SelectionView save(Authentication authentication, SelectionRequest request) {
        UUID accountId = accountId(authentication);
        if (settings.seasonId() == null) throw conflict("No selection season is configured");
        if (!settings.seasonId().equals(request.seasonId())) throw conflict("Season changed; reload selection");
        // Serialize first creation and subsequent writes across this account's devices.
        var account = accountRepository.findForSelection(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!"ACTIVE".equals(account.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var season = seasons.findForSelection(request.seasonId())
                .orElseThrow(() -> unavailable("Configured season does not exist"));
        if (!Objects.equals(season.getVersion(), request.seasonVersion())) throw conflict("Season changed; reload selection");
        var selection = selections.findByAccountIdAndSeasonId(accountId, season.getId()).orElse(null);
        if (!Objects.equals(selection == null ? null : selection.getVersion(), request.expectedVersion())) {
            throw conflict("Selection changed on another device; reload selection");
        }
        CharacterIdentity character;
        if (season.getRatingSubjectType() == RatingSubjectType.ACCOUNT) {
            if (request.characterId() != null || request.declaration() == null) {
                throw badRequest("An account season requires a declaration, not a provider character");
            }
            var declaration = request.declaration();
            character = selection == null ? null : identities.findById(selection.getCharacterId()).orElseThrow();
            if (character == null || character.getSource() != CharacterIdentity.Source.DECLARED
                    || !character.getName().equals(declaration.name().strip()) || !character.getRealm().equals(declaration.realm().strip())) {
                character = identities.saveAndFlush(CharacterIdentity.declared(accountId,
                        declaration.name().strip(), declaration.realm().strip()));
            }
        } else {
            if (request.characterId() == null || request.declaration() != null) {
                throw badRequest("A character season requires a verified internal character reference");
            }
            character = identities.findById(request.characterId())
                    .filter(identity -> identity.getAccountId().equals(accountId)
                            && identity.getSource() == CharacterIdentity.Source.BLIZZARD_VERIFIED
                            && "WOW_FOREVER".equals(identity.getProduct()) && "EU".equals(identity.getRegion()))
                    .orElseThrow(() -> badRequest("Character is not associated with this account in Forever"));
            verifier.requireOwnership(accountId, character, authentication);
        }
        if (selection == null) selection = new SeasonSelection(accountId, season.getId());
        selection.choose(character.getId(), season.getRatingSubjectType(), request.role(), request.captainConsent());
        return view(selections.saveAndFlush(selection), season);
    }

    private UUID accountId(Authentication authentication) {
        var profile = accounts.currentUser(authentication);
        if (profile == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return profile.id();
    }
    private SelectionView view(SeasonSelection selection, SeasonEntity season) {
        var character = identities.findById(selection.getCharacterId()).orElseThrow();
        boolean valid = selection.getRatingSubjectType() == season.getRatingSubjectType()
                && (season.getRatingSubjectType() == RatingSubjectType.ACCOUNT
                    ? character.getSource() == CharacterIdentity.Source.DECLARED
                    : character.getSource() == CharacterIdentity.Source.BLIZZARD_VERIFIED);
        UUID subject = selection.getRatingSubjectType() == RatingSubjectType.ACCOUNT
                ? selection.getAccountId() : character.getId();
        return new SelectionView(character.getId(), character.getName(), character.getRealm(), character.getSource(),
                selection.getRatingSubjectType(), subject, selection.getMatchRole(), selection.isCaptainConsent(),
                selection.getVersion(), valid);
    }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException unavailable(String reason) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason); }
}
