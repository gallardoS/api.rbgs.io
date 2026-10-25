ALTER TABLE rbgs.matches DROP CONSTRAINT capture_test_matches_verification_status_check;
ALTER TABLE rbgs.matches ADD CONSTRAINT matches_verification_status_check
    CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REVIEW', 'RESOLVED', 'VOIDED'));
ALTER TABLE rbgs.matches
    ADD COLUMN decision_reason VARCHAR(2000),
    ADD COLUMN decision_at TIMESTAMPTZ,
    ADD COLUMN decision_evidence_ids TEXT;

ALTER TABLE rbgs.match_result_events
    ADD COLUMN actor_id UUID,
    ADD COLUMN decision_reason VARCHAR(2000),
    ADD COLUMN expected_version BIGINT,
    ADD COLUMN previous_reason VARCHAR(128),
    ADD COLUMN previous_outcome_kind VARCHAR(8),
    ADD COLUMN previous_winning_team VARCHAR(1),
    ADD COLUMN correction_required BOOLEAN NOT NULL DEFAULT FALSE;
