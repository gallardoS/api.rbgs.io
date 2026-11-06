ALTER TABLE rbgs.accounts ADD COLUMN avatar_style VARCHAR(2)
    CHECK (avatar_style IN ('sd', 'hd'));

CREATE FUNCTION rbgs.lobby_avatar_style_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.avatar_style IS DISTINCT FROM NEW.avatar_style THEN
        PERFORM rbgs.notify_account_lobby(NEW.id);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_avatar_style_changed AFTER UPDATE OF avatar_style ON rbgs.accounts
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_avatar_style_changed();
