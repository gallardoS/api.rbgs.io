-- Backfill unset roles without changing any existing FC/healer/DPS choice.
-- Bump optimistic versions so a pre-migration client must reload before replacing these selections.
UPDATE rbgs.group_members SET display_role = 'DPS', display_role_version = display_role_version + 1
    WHERE display_role IS NULL;
ALTER TABLE rbgs.group_members ALTER COLUMN display_role SET DEFAULT 'DPS';
ALTER TABLE rbgs.group_members ALTER COLUMN display_role SET NOT NULL;

UPDATE rbgs.season_selections SET match_role = 'DPS', version = version + 1
    WHERE match_role IS NULL;
ALTER TABLE rbgs.season_selections ALTER COLUMN match_role SET DEFAULT 'DPS';
ALTER TABLE rbgs.season_selections ALTER COLUMN match_role SET NOT NULL;
