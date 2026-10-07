CREATE TABLE rbgs.season_subscriptions (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    language VARCHAR(2) NOT NULL CHECK (language IN ('en', 'es')),
    confirmation_hash VARCHAR(64) NOT NULL UNIQUE,
    confirmation_expires_at TIMESTAMPTZ NOT NULL,
    unsubscribe_hash VARCHAR(64) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMPTZ,
    unsubscribed_at TIMESTAMPTZ,
    consent_version VARCHAR(32) NOT NULL DEFAULT 'season-launch-v1'
);

CREATE TABLE rbgs.season_notification_campaign (
    id VARCHAR(32) PRIMARY KEY CHECK (id = 'first-launch'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rbgs.season_notification_outbox (
    id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES rbgs.season_subscriptions(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CONFIRMATION', 'LAUNCH')),
    subject VARCHAR(200) NOT NULL,
    html TEXT,
    plain_text TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'INFLIGHT', 'SENT', 'CANCELLED', 'REVIEW')),
    attempts INTEGER NOT NULL DEFAULT 0,
    first_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    provider_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX season_one_launch_per_subscriber
    ON rbgs.season_notification_outbox(subscription_id) WHERE kind = 'LAUNCH';
CREATE INDEX season_outbox_due ON rbgs.season_notification_outbox(next_attempt_at)
    WHERE status IN ('PENDING', 'INFLIGHT');
