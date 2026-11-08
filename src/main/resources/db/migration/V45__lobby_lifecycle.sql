ALTER TABLE rbgs.group_members ADD COLUMN browser_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE rbgs.premade_groups ADD COLUMN last_match_activity_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX lobby_browser_expiry ON rbgs.group_members(browser_seen_at, group_id);
CREATE INDEX lobby_idle_expiry ON rbgs.premade_groups(last_match_activity_at);
CREATE INDEX lobby_match_activity ON rbgs.premade_queue_entries(proposal_id) WHERE proposal_id IS NOT NULL;

-- Presence pings must not make every lobby member reload their roster.
DROP TRIGGER lobby_membership_changed ON rbgs.group_members;
CREATE TRIGGER lobby_membership_changed
    AFTER INSERT OR UPDATE OF account_id, group_id, joined_order, display_role, display_role_version,
        ready_revision, ready_season_id, ready_season_version, ready_selection_version OR DELETE
    ON rbgs.group_members FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_membership_changed();

CREATE FUNCTION rbgs.record_lobby_match_activity(p_room UUID) RETURNS void LANGUAGE sql AS $$
    UPDATE rbgs.premade_groups g SET last_match_activity_at = clock_timestamp()
    WHERE EXISTS (SELECT 1 FROM rbgs.premade_queue_entries e WHERE e.group_id = g.id AND e.proposal_id = p_room);
$$;

CREATE FUNCTION rbgs.lobby_room_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status IN ('PLAYING', 'AWAITING_EVIDENCE') THEN
        PERFORM rbgs.record_lobby_match_activity(NEW.id);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_room_activity AFTER UPDATE OF status ON rbgs.match_rooms
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_room_activity();

CREATE FUNCTION rbgs.lobby_competition_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.cancelled_at IS NULL AND
        ((OLD.competition_ended_at IS NULL AND NEW.competition_ended_at IS NOT NULL)
         OR (OLD.verification_status NOT IN ('VERIFIED', 'RESOLVED') AND NEW.verification_status IN ('VERIFIED', 'RESOLVED'))) THEN
        PERFORM rbgs.record_lobby_match_activity((SELECT id FROM rbgs.match_rooms WHERE match_id = NEW.id));
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lobby_competition_activity AFTER UPDATE OF competition_ended_at, verification_status ON rbgs.matches
    FOR EACH ROW EXECUTE FUNCTION rbgs.lobby_competition_activity();
