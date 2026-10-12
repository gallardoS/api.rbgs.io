ALTER TABLE rbgs.devices ADD COLUMN last_seen_at timestamptz;
ALTER TABLE rbgs.device_pairings ADD COLUMN approval_hash varchar(64);
ALTER TABLE rbgs.device_pairings ADD COLUMN device_id uuid REFERENCES rbgs.devices(id);
CREATE UNIQUE INDEX device_pairing_approval_hash ON rbgs.device_pairings(approval_hash) WHERE approval_hash IS NOT NULL;
