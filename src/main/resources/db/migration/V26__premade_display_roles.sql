ALTER TABLE rbgs.group_members
    ADD COLUMN display_role VARCHAR(10) CHECK (display_role IN ('FC', 'HEALER', 'DPS')),
    ADD COLUMN display_role_version BIGINT NOT NULL DEFAULT 0 CHECK (display_role_version >= 0);
-- Intentionally do not copy the competitive SoloQ selection into this optional lobby label.
