CREATE TABLE rbgs.character_identities (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id),
    product VARCHAR(32) NOT NULL CHECK (product = 'WOW_FOREVER'),
    region VARCHAR(16) NOT NULL CHECK (region = 'EU'),
    source VARCHAR(32) NOT NULL CHECK (source IN ('DECLARED', 'BLIZZARD_VERIFIED')),
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
    realm VARCHAR(100) NOT NULL CHECK (length(trim(realm)) > 0),
    provider_namespace VARCHAR(100),
    provider_realm_id VARCHAR(100),
    provider_character_id VARCHAR(100),
    UNIQUE (account_id, id),
    UNIQUE (account_id, product, region, provider_namespace, provider_realm_id, provider_character_id),
    CHECK ((source = 'DECLARED' AND provider_namespace IS NULL AND provider_realm_id IS NULL AND provider_character_id IS NULL)
        OR (source = 'BLIZZARD_VERIFIED' AND provider_namespace IS NOT NULL AND provider_realm_id IS NOT NULL AND provider_character_id IS NOT NULL))
);

CREATE TABLE rbgs.season_selections (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id),
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id),
    character_id UUID NOT NULL,
    rating_subject_type VARCHAR(16) NOT NULL CHECK (rating_subject_type IN ('ACCOUNT', 'CHARACTER')),
    match_role VARCHAR(16) NOT NULL CHECK (match_role IN ('FC', 'HEALER', 'DPS')),
    captain_consent BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (account_id, season_id),
    FOREIGN KEY (account_id, character_id) REFERENCES rbgs.character_identities(account_id, id)
);
