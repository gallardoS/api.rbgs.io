ALTER TABLE rbgs.premade_groups ADD COLUMN join_code VARCHAR(22);
ALTER TABLE rbgs.premade_groups ADD COLUMN join_code_hash VARCHAR(64) UNIQUE;
ALTER TABLE rbgs.premade_groups ADD CONSTRAINT lobby_code_pair
    CHECK ((join_code IS NULL) = (join_code_hash IS NULL));

CREATE TABLE rbgs.group_join_limits (
    account_id UUID PRIMARY KEY REFERENCES rbgs.accounts(id) ON DELETE CASCADE,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0 AND attempts <= 10)
);
