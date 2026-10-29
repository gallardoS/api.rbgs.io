-- Transactional invalidations only: PostgreSQL delivers these after COMMIT, never on rollback.
-- Payloads contain an account ID, never codes, names, credentials or lobby snapshots.
CREATE FUNCTION rbgs.notify_lobby_account(p_account UUID) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF p_account IS NOT NULL THEN PERFORM pg_notify('rbgs_lobby_changes', p_account::text); END IF;
END $$;
CREATE FUNCTION rbgs.notify_lobby_members(p_group UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE recipient UUID;
BEGIN
    FOR recipient IN SELECT account_id FROM rbgs.group_members WHERE group_id = p_group LOOP
        PERFORM rbgs.notify_lobby_account(recipient);
    END LOOP;
END $$;
CREATE FUNCTION rbgs.notify_account_lobby(p_account UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE membership UUID;
BEGIN
    PERFORM rbgs.notify_lobby_account(p_account);
    SELECT group_id INTO membership FROM rbgs.group_members WHERE account_id = p_account;
    IF membership IS NOT NULL THEN PERFORM rbgs.notify_lobby_members(membership); END IF;
END $$;
CREATE FUNCTION rbgs.lobby_membership_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM rbgs.notify_lobby_account(OLD.account_id);
        PERFORM rbgs.notify_lobby_members(OLD.group_id);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        PERFORM rbgs.notify_lobby_account(NEW.account_id);
        PERFORM rbgs.notify_lobby_members(NEW.group_id);
        RETURN NEW;
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER lobby_membership_changed AFTER INSERT OR UPDATE OR DELETE ON rbgs.group_members
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_membership_changed();
CREATE FUNCTION rbgs.lobby_metadata_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE recipient UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM rbgs.notify_lobby_members(OLD.id);
        PERFORM rbgs.notify_lobby_account(OLD.captain_id);
        RETURN OLD;
    END IF;
    PERFORM rbgs.notify_lobby_members(NEW.id);
    PERFORM rbgs.notify_lobby_account(NEW.captain_id);
    IF TG_OP = 'UPDATE' AND OLD.captain_id IS DISTINCT FROM NEW.captain_id THEN
        FOR recipient IN SELECT account_id FROM rbgs.group_invitations WHERE group_id = NEW.id AND expires_at > CURRENT_TIMESTAMP LOOP
            PERFORM rbgs.notify_lobby_account(recipient);
        END LOOP;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_metadata_changed AFTER INSERT OR UPDATE ON rbgs.premade_groups
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_metadata_changed();
CREATE TRIGGER lobby_metadata_deleted BEFORE DELETE ON rbgs.premade_groups
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_metadata_changed();
CREATE FUNCTION rbgs.lobby_invitation_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lobby UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM rbgs.notify_lobby_account(OLD.account_id); lobby := OLD.group_id;
    ELSE
        PERFORM rbgs.notify_lobby_account(NEW.account_id); lobby := NEW.group_id;
    END IF;
    PERFORM rbgs.notify_lobby_account((SELECT captain_id FROM rbgs.premade_groups WHERE id = lobby));
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_invitation_changed AFTER INSERT OR UPDATE OR DELETE ON rbgs.group_invitations
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_invitation_changed();
CREATE FUNCTION rbgs.lobby_selection_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN PERFORM rbgs.notify_account_lobby(OLD.account_id); RETURN OLD; END IF;
    PERFORM rbgs.notify_account_lobby(NEW.account_id); RETURN NEW;
END $$;
CREATE TRIGGER lobby_selection_changed AFTER INSERT OR UPDATE OR DELETE ON rbgs.season_selections
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_selection_changed();
CREATE FUNCTION rbgs.lobby_device_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Ordinary heartbeats do not invalidate the entire lobby. Clock-based expiry uses the health refresh.
    IF OLD.revoked_at IS DISTINCT FROM NEW.revoked_at OR OLD.removed_at IS DISTINCT FROM NEW.removed_at
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at OR OLD.granted_scope IS DISTINCT FROM NEW.granted_scope
        OR (OLD.last_seen_at IS NULL AND NEW.last_seen_at IS NOT NULL)
        OR NEW.last_seen_at > OLD.last_seen_at + INTERVAL '45 seconds' THEN
        PERFORM rbgs.notify_account_lobby(NEW.account_id);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_device_changed AFTER UPDATE ON rbgs.devices
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_device_changed();
CREATE FUNCTION rbgs.lobby_account_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.account_status IS DISTINCT FROM NEW.account_status OR OLD.display_name IS DISTINCT FROM NEW.display_name THEN
        PERFORM rbgs.notify_account_lobby(NEW.id);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_account_changed AFTER UPDATE ON rbgs.accounts
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_account_changed();
