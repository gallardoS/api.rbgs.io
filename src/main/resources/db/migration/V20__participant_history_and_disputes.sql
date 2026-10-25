CREATE TABLE rbgs.match_disputes (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES rbgs.matches(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id),
    status VARCHAR(24) NOT NULL CHECK (status IN ('OPEN', 'WAITING_PLAYER', 'RESOLVED')),
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    opened_for_version BIGINT NOT NULL,
    last_moderator_at TIMESTAMPTZ,
    seen_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(match_id, account_id)
);
CREATE INDEX match_disputes_match ON rbgs.match_disputes(match_id, opened_at);
CREATE TABLE rbgs.match_dispute_messages (
    id UUID PRIMARY KEY,
    dispute_id UUID NOT NULL REFERENCES rbgs.match_disputes(id) ON DELETE CASCADE,
    author_id UUID NOT NULL,
    author_role VARCHAR(16) NOT NULL CHECK (author_role IN ('PLAYER', 'MODERATOR', 'SYSTEM')),
    kind VARCHAR(32) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX match_dispute_messages_thread ON rbgs.match_dispute_messages(dispute_id, recorded_at, id);
CREATE TABLE rbgs.match_history_reads (
    assignment_id UUID PRIMARY KEY REFERENCES rbgs.match_assignments(id) ON DELETE CASCADE,
    seen_version BIGINT NOT NULL
);
