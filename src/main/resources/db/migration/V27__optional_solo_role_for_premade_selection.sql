-- Premade character selection need not invent a competitive SoloQ role.
ALTER TABLE rbgs.season_selections ALTER COLUMN match_role DROP NOT NULL;
