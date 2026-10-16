-- Device identity, authorization and revocation use UUIDs and credentials.
ALTER TABLE rbgs.device_pairings DROP COLUMN name;
ALTER TABLE rbgs.devices DROP COLUMN name;
