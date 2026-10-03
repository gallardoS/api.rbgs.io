CREATE TABLE rbgs.accounts (
    id UUID PRIMARY KEY,
    provider_issuer VARCHAR(255) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    region VARCHAR(16) NOT NULL,
    account_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    account_role VARCHAR(16) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accounts_provider_identity_unique UNIQUE (provider_issuer, provider_subject),
    CONSTRAINT accounts_status_valid CHECK (account_status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT accounts_role_valid CHECK (account_role IN ('USER', 'MODERATOR'))
);
