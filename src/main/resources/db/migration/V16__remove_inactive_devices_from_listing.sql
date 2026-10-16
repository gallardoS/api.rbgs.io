ALTER TABLE rbgs.devices ADD COLUMN removed_at timestamptz;
CREATE INDEX devices_visible_account ON rbgs.devices(account_id, created_at DESC) WHERE removed_at IS NULL;
