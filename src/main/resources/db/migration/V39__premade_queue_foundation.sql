-- Internal premade snapshots only. No real-player admission or scheduler is enabled here.
CREATE TABLE rbgs.premade_queue_entries (
    id UUID PRIMARY KEY,
    -- Keep historical snapshots after a lobby is closed.
    group_id UUID NOT NULL,
    entered_at TIMESTAMPTZ NOT NULL,
    snapshot TEXT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','PROPOSED','CANCELLED')),
    proposal_id UUID REFERENCES rbgs.queue_proposals(id),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((state='PROPOSED') = (proposal_id IS NOT NULL))
);
CREATE UNIQUE INDEX premade_one_waiting_group ON rbgs.premade_queue_entries(group_id) WHERE state='QUEUED';
ALTER TABLE rbgs.queue_tickets ADD COLUMN premade_entry_id UUID REFERENCES rbgs.premade_queue_entries(id);
CREATE INDEX premade_ticket_members ON rbgs.queue_tickets(premade_entry_id);
