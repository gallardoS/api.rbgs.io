CREATE TABLE rbgs.devices (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES rbgs.accounts(id),
    name varchar(80) NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX devices_account ON rbgs.devices(account_id);
CREATE TABLE rbgs.device_pairings (
    id uuid PRIMARY KEY,
    code_hash varchar(64) NOT NULL UNIQUE,
    polling_hash varchar(64) NOT NULL UNIQUE,
    name varchar(80) NOT NULL,
    expires_at timestamptz NOT NULL,
    account_id uuid REFERENCES rbgs.accounts(id),
    consumed_at timestamptz
);
