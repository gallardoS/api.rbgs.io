ALTER TABLE rbgs.queue_tickets
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SIMULATION' CHECK (source IN ('SIMULATION','FOREVER')),
    ADD COLUMN season_version BIGINT,
    ADD COLUMN selection_version BIGINT,
    ADD COLUMN companion_device_id UUID REFERENCES rbgs.devices(id),
    ADD COLUMN cancellation_reason VARCHAR(64),
    ADD CONSTRAINT real_queue_versions CHECK (source <> 'FOREVER' OR
        (season_version IS NOT NULL AND selection_version IS NOT NULL AND companion_device_id IS NOT NULL));
ALTER TABLE rbgs.match_rooms ADD COLUMN match_id UUID UNIQUE REFERENCES rbgs.matches(id);
CREATE INDEX queue_source_waiting ON rbgs.queue_tickets(source,entered_at) WHERE state='QUEUED';
CREATE OR REPLACE FUNCTION rbgs.notify_solo_queue_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source='FOREVER' THEN PERFORM pg_notify('rbgs_solo_queue',NEW.account_id::text); END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER solo_queue_ticket_change AFTER INSERT OR UPDATE ON rbgs.queue_tickets
    FOR EACH ROW EXECUTE FUNCTION rbgs.notify_solo_queue_change();
CREATE OR REPLACE FUNCTION rbgs.notify_solo_room_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE participant RECORD;
BEGIN
    IF NEW.source='FOREVER' THEN
        FOR participant IN SELECT account_id FROM rbgs.queue_tickets WHERE proposal_id=NEW.id LOOP
            PERFORM pg_notify('rbgs_solo_queue',participant.account_id::text);
        END LOOP;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER solo_queue_room_change AFTER UPDATE ON rbgs.match_rooms
    FOR EACH ROW EXECUTE FUNCTION rbgs.notify_solo_room_change();
