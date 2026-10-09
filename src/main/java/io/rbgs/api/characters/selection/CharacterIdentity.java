package io.rbgs.api.characters.selection;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "character_identities", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterIdentity {
    public enum Source { DECLARED, BLIZZARD_VERIFIED }
    @Id private UUID id;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(nullable = false, length = 32) private String product;
    @Column(nullable = false, length = 16) private String region;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) private Source source;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, length = 100) private String realm;
    @Column(name = "provider_namespace", length = 100) private String providerNamespace;
    @Column(name = "provider_realm_id", length = 100) private String providerRealmId;
    @Column(name = "provider_character_id", length = 100) private String providerCharacterId;

    public static CharacterIdentity declared(UUID accountId, String name, String realm) {
        var character = new CharacterIdentity();
        character.id = UUID.randomUUID();
        character.accountId = Objects.requireNonNull(accountId);
        character.product = "WOW_FOREVER";
        character.region = "EU";
        character.source = Source.DECLARED;
        character.name = Objects.requireNonNull(name);
        character.realm = Objects.requireNonNull(realm);
        return character;
    }
}
