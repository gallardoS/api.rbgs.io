ALTER TABLE rbgs.capture_test_matches RENAME TO matches;
ALTER TABLE rbgs.capture_assignments RENAME TO match_assignments;
ALTER TABLE rbgs.capture_result_events RENAME TO match_result_events;

ALTER TABLE rbgs.matches RENAME COLUMN arm_until TO capture_start_deadline;
ALTER TABLE rbgs.matches RENAME COLUMN test_kind TO evidence_policy;
ALTER TABLE rbgs.matches DROP CONSTRAINT capture_test_matches_test_kind_check;
ALTER TABLE rbgs.matches ALTER COLUMN evidence_policy DROP DEFAULT;
UPDATE rbgs.matches SET evidence_policy='FULL_ROSTER' WHERE evidence_policy='ROSTER_CONTROLLED';
ALTER TABLE rbgs.matches ADD CONSTRAINT matches_evidence_policy_check
    CHECK (evidence_policy IN ('FULL_ROSTER', 'CAPTURE_ONLY'));

ALTER TABLE rbgs.matches ADD COLUMN mode VARCHAR(24) NOT NULL DEFAULT 'CONTROLLED_TEST'
    CHECK (mode IN ('CONTROLLED_TEST', 'MATCHMADE'));
ALTER TABLE rbgs.matches ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE rbgs.matches ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE rbgs.matches DROP CONSTRAINT capture_test_matches_profile_check;
UPDATE rbgs.matches SET verification_deadline=capture_start_deadline + interval '4 hours 15 minutes'
    WHERE verification_deadline IS NULL;

ALTER INDEX rbgs.capture_test_matches_pkey RENAME TO matches_pkey;
ALTER INDEX rbgs.capture_assignments_pkey RENAME TO match_assignments_pkey;
ALTER INDEX rbgs.capture_assignments_owner RENAME TO match_assignments_owner;
ALTER INDEX rbgs.capture_result_events_pkey RENAME TO match_result_events_pkey;
ALTER INDEX rbgs.capture_result_events_match RENAME TO match_result_events_match;
ALTER INDEX rbgs.capture_verification_due RENAME TO match_verification_due;
