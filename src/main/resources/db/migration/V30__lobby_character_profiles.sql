-- Share only the selected provider character, never the account-wide catalog or OAuth credentials.
-- Existing selections stay valid; Play refreshes this optional snapshot through the verified picker.
ALTER TABLE rbgs.play_characters ADD COLUMN character_profile JSONB
    CHECK (character_profile IS NULL OR jsonb_typeof(character_profile) = 'object');
