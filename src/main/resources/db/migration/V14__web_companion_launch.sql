CREATE TABLE rbgs.device_launches (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES rbgs.accounts(id),
    secret_hash varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    claim_hash varchar(64),
    device_id uuid REFERENCES rbgs.devices(id),
    issued_credential boolean NOT NULL DEFAULT false
);
CREATE INDEX device_launches_expiry ON rbgs.device_launches(expires_at);
