CREATE TABLE rbgs.capture_test_matches (
    id UUID PRIMARY KEY, created_by UUID NOT NULL REFERENCES rbgs.accounts(id), created_at TIMESTAMPTZ NOT NULL,
    arm_until TIMESTAMPTZ NOT NULL, expected_roster TEXT NOT NULL, cancelled_at TIMESTAMPTZ,
    profile VARCHAR(32) NOT NULL CHECK (profile='DARKSPEAR_DIAGNOSTIC')
);
CREATE TABLE rbgs.capture_assignments (
    id UUID PRIMARY KEY, match_id UUID NOT NULL REFERENCES rbgs.capture_test_matches(id),
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id), capture_id UUID NOT NULL UNIQUE,
    character_id UUID NOT NULL, assigned_team VARCHAR(1) NOT NULL CHECK (assigned_team IN ('A','B')),
    code_hash VARCHAR(64), issued_at TIMESTAMPTZ, UNIQUE(match_id,account_id),
    CHECK ((code_hash IS NULL AND issued_at IS NULL) OR (code_hash IS NOT NULL AND issued_at IS NOT NULL))
);
CREATE INDEX capture_assignments_owner ON rbgs.capture_assignments(account_id);
CREATE TABLE rbgs.capture_evidence (
    id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES rbgs.accounts(id),
    device_id UUID NOT NULL REFERENCES rbgs.devices(id), assignment_id UUID NOT NULL REFERENCES rbgs.capture_assignments(id),
    capture_id UUID NOT NULL, revision INTEGER NOT NULL CHECK (revision > 0), received_at TIMESTAMPTZ NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL, stored_sha256 VARCHAR(64) NOT NULL,
    redacted_payload TEXT NOT NULL,
    processing_status VARCHAR(16) NOT NULL CHECK (processing_status IN ('PENDING','REVIEW')),
    review_reason VARCHAR(128), processed_at TIMESTAMPTZ,
    UNIQUE(account_id,assignment_id,capture_id,revision)
);
CREATE TABLE rbgs.capture_processing_jobs (
    evidence_id UUID PRIMARY KEY REFERENCES rbgs.capture_evidence(id), created_at TIMESTAMPTZ NOT NULL
);
