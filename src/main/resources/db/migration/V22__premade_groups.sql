ALTER TABLE rbgs.season_selections ADD COLUMN companion_device_id UUID REFERENCES rbgs.devices(id);

CREATE TABLE rbgs.premade_groups (
    id UUID PRIMARY KEY,
    captain_id UUID NOT NULL REFERENCES rbgs.accounts(id),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
CREATE TABLE rbgs.group_members (
    account_id UUID PRIMARY KEY REFERENCES rbgs.accounts(id),
    group_id UUID NOT NULL REFERENCES rbgs.premade_groups(id) ON DELETE CASCADE,
    ready_revision BIGINT,
    ready_season_id UUID,
    ready_season_version BIGINT,
    ready_selection_version BIGINT,
    UNIQUE(group_id, account_id)
);
ALTER TABLE rbgs.premade_groups ADD CONSTRAINT group_captain_is_member
    FOREIGN KEY (id, captain_id) REFERENCES rbgs.group_members(group_id, account_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE rbgs.group_invitations (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES rbgs.premade_groups(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE(group_id, account_id)
);
CREATE INDEX group_invitations_recipient ON rbgs.group_invitations(account_id);
