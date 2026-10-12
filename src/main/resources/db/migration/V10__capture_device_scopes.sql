ALTER TABLE rbgs.devices ADD COLUMN granted_scope VARCHAR(128) NOT NULL DEFAULT 'companion:status';
ALTER TABLE rbgs.device_pairings ADD COLUMN requested_scope VARCHAR(128) NOT NULL DEFAULT 'companion:status';
ALTER TABLE rbgs.devices ADD CONSTRAINT device_scope_known CHECK (granted_scope IN ('companion:status', 'companion:status companion:assignments evidence:write'));
ALTER TABLE rbgs.device_pairings ADD CONSTRAINT pairing_scope_known CHECK (requested_scope IN ('companion:status', 'companion:status companion:assignments evidence:write'));
