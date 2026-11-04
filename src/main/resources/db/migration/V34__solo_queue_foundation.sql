-- No production admission/worker is enabled by this migration.
-- Explicit end-of-play fact, independent of result verification/rating (D01).
ALTER TABLE rbgs.matches ADD COLUMN competition_ended_at TIMESTAMPTZ;
CREATE TABLE rbgs.queue_coordinator (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO rbgs.queue_coordinator VALUES (1);

CREATE TABLE rbgs.queue_proposals (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    snapshot TEXT NOT NULL
);
CREATE TABLE rbgs.queue_tickets (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id),
    character_id UUID NOT NULL,
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id),
    queue_type VARCHAR(16) NOT NULL CHECK (queue_type IN ('SOLO', 'PREMADE')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED', 'PROPOSED', 'CANCELLED')),
    entered_at TIMESTAMPTZ NOT NULL,
    snapshot TEXT NOT NULL,
    proposal_id UUID REFERENCES rbgs.queue_proposals(id),
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (account_id, character_id) REFERENCES rbgs.character_identities(account_id, id),
    CHECK ((state = 'PROPOSED') = (proposal_id IS NOT NULL))
);
-- One reservation across characters, devices, seasons and queue types, including room handoff.
CREATE UNIQUE INDEX queue_one_active_account ON rbgs.queue_tickets(account_id)
    WHERE state IN ('QUEUED', 'PROPOSED');
CREATE INDEX queue_waiting_order ON rbgs.queue_tickets(entered_at, account_id) WHERE state = 'QUEUED';
