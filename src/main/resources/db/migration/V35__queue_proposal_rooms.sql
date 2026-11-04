CREATE TABLE rbgs.match_rooms (
    id UUID PRIMARY KEY REFERENCES rbgs.queue_proposals(id),
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id),
    source VARCHAR(16) NOT NULL CHECK (source IN ('SIMULATION', 'FOREVER')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROPOSED','PREPARING','CANCELLED')),
    acceptance_deadline TIMESTAMPTZ NOT NULL,
    preparation_deadline TIMESTAMPTZ,
    captain_a UUID NOT NULL REFERENCES rbgs.accounts(id),
    captain_b UUID NOT NULL REFERENCES rbgs.accounts(id),
    cancellation_reason VARCHAR(64),
    accepted_count INTEGER NOT NULL DEFAULT 0 CHECK (accepted_count BETWEEN 0 AND 20),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (captain_a <> captain_b),
    CHECK (status <> 'PREPARING' OR preparation_deadline IS NOT NULL)
);
ALTER TABLE rbgs.queue_tickets ADD COLUMN accepted_at TIMESTAMPTZ;
DO $$ DECLARE constraint_name TEXT; BEGIN
    SELECT conname INTO constraint_name FROM pg_constraint
      WHERE conrelid='rbgs.queue_tickets'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%proposal_id%';
    IF constraint_name IS NOT NULL THEN
      EXECUTE format('ALTER TABLE rbgs.queue_tickets DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;
ALTER TABLE rbgs.queue_tickets ADD CONSTRAINT queue_proposal_state
    CHECK ((state='PROPOSED' AND proposal_id IS NOT NULL)
        OR state='CANCELLED' OR (state='QUEUED' AND proposal_id IS NULL));
CREATE INDEX room_deadlines ON rbgs.match_rooms(status,acceptance_deadline,preparation_deadline);
CREATE INDEX queue_proposal_members ON rbgs.queue_tickets(proposal_id);

-- Populated exclusively by the explicitly isolated local simulator.
CREATE TABLE rbgs.soloq_lab_state (
    id INTEGER PRIMARY KEY CHECK (id=1),
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id),
    generation UUID NOT NULL,
    scenario VARCHAR(32) NOT NULL CHECK (scenario IN ('NORMAL','NO_CAPTAINS','NO_HEALERS')),
    version BIGINT NOT NULL DEFAULT 0
);
