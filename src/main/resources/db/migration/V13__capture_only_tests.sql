ALTER TABLE rbgs.capture_test_matches
    ADD COLUMN test_kind VARCHAR(24) NOT NULL DEFAULT 'ROSTER_CONTROLLED'
        CHECK (test_kind IN ('ROSTER_CONTROLLED', 'CAPTURE_ONLY'));

ALTER TABLE rbgs.capture_assignments ALTER COLUMN assigned_team DROP NOT NULL;
