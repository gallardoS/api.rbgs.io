CREATE TABLE rbgs.play_characters (
    account_id UUID PRIMARY KEY REFERENCES rbgs.accounts(id) ON DELETE CASCADE,
    namespace VARCHAR(100) NOT NULL CHECK (namespace IN ('profile-classic1x-eu', 'profile-classic-eu', 'profile-classicann-eu', 'profile-classic1x-us', 'profile-classic-us', 'profile-classicann-us')),
    wow_account_id BIGINT NOT NULL,
    realm_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    region VARCHAR(2) NOT NULL CHECK (region IN ('EU', 'NA')),
    name VARCHAR(100) NOT NULL,
    realm VARCHAR(100) NOT NULL,
    CHECK (region = CASE WHEN namespace LIKE '%-us' THEN 'NA' ELSE 'EU' END)
);

ALTER TABLE rbgs.premade_groups ADD COLUMN region VARCHAR(2);
-- Legacy persisted character identities were constrained to EU (V8).
UPDATE rbgs.premade_groups SET region = 'EU';
ALTER TABLE rbgs.premade_groups ALTER COLUMN region SET NOT NULL;
ALTER TABLE rbgs.premade_groups ADD CONSTRAINT lobby_region_supported CHECK (region IN ('EU', 'NA'));

CREATE FUNCTION rbgs.require_play_character_region() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM rbgs.group_members m JOIN rbgs.premade_groups g ON g.id = m.group_id
            WHERE m.account_id = NEW.account_id AND g.region <> NEW.region) THEN
        RAISE EXCEPTION 'Character and lobby regions differ' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER play_character_region BEFORE INSERT OR UPDATE ON rbgs.play_characters
    FOR EACH ROW EXECUTE FUNCTION rbgs.require_play_character_region();
CREATE FUNCTION rbgs.require_member_region() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM rbgs.play_characters p JOIN rbgs.premade_groups g ON g.id = NEW.group_id
            WHERE p.account_id = NEW.account_id AND p.region = g.region) THEN
        RAISE EXCEPTION 'Select a character from the lobby region' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER member_region BEFORE INSERT OR UPDATE OF group_id ON rbgs.group_members
    FOR EACH ROW EXECUTE FUNCTION rbgs.require_member_region();
CREATE FUNCTION rbgs.keep_lobby_region() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.region IS DISTINCT FROM NEW.region THEN
        RAISE EXCEPTION 'Lobby region is fixed at creation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_region_immutable BEFORE UPDATE OF region ON rbgs.premade_groups
    FOR EACH ROW EXECUTE FUNCTION rbgs.keep_lobby_region();

CREATE FUNCTION rbgs.play_character_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM rbgs.notify_account_lobby(NEW.account_id);
    RETURN NEW;
END $$;
CREATE TRIGGER play_character_changed AFTER INSERT OR UPDATE ON rbgs.play_characters
    FOR EACH ROW EXECUTE FUNCTION rbgs.play_character_changed();
