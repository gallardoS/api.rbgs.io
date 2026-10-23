ALTER TABLE rbgs.capture_test_matches
    ADD COLUMN verification_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REVIEW')),
    ADD COLUMN verification_reason VARCHAR(128),
    ADD COLUMN winning_team VARCHAR(1) CHECK (winning_team IN ('A', 'B')),
    ADD COLUMN outcome_kind VARCHAR(8) CHECK (outcome_kind IN ('WIN', 'DRAW')),
    ADD COLUMN reports_a INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reports_b INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN verification_deadline TIMESTAMPTZ,
    ADD COLUMN result_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE rbgs.capture_evidence
    ADD COLUMN validation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (validation_status IN ('PENDING', 'VALID', 'INVALID', 'INCOMPLETE', 'UNVERIFIABLE')),
    ADD COLUMN normalized_facts TEXT;

CREATE TABLE rbgs.capture_result_events (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES rbgs.capture_test_matches(id) ON DELETE CASCADE,
    recorded_at TIMESTAMPTZ NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(128),
    outcome_kind VARCHAR(8),
    winning_team VARCHAR(1),
    evidence_ids TEXT NOT NULL,
    policy_version VARCHAR(32) NOT NULL
);
CREATE INDEX capture_result_events_match ON rbgs.capture_result_events(match_id, recorded_at);
CREATE INDEX capture_verification_due ON rbgs.capture_test_matches(verification_deadline)
    WHERE verification_status='PENDING';

INSERT INTO rbgs.capture_processing_jobs(evidence_id, created_at)
SELECT e.id, e.received_at FROM rbgs.capture_evidence e
ON CONFLICT (evidence_id) DO NOTHING;
