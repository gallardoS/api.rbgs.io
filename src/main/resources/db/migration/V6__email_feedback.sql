CREATE TABLE rbgs.email_suppressions (
    email VARCHAR(254) PRIMARY KEY,
    reason VARCHAR(24) NOT NULL CHECK (reason IN ('PERMANENT_BOUNCE', 'COMPLAINT')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rbgs.email_feedback_events (
    id UUID PRIMARY KEY,
    provider_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    detail VARCHAR(64) NOT NULL,
    recipient_count INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX email_feedback_received ON rbgs.email_feedback_events(received_at);
