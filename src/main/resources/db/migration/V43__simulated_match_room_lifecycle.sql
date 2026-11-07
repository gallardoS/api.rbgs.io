ALTER TABLE rbgs.match_rooms
    DROP CONSTRAINT match_rooms_status_check,
    ALTER COLUMN status TYPE VARCHAR(24),
    ADD COLUMN captain_a_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN captain_b_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN simulated_winner VARCHAR(1),
    ADD CONSTRAINT room_lifecycle_status CHECK (
        status IN ('PROPOSED','PREPARING','CANCELLED')
        OR (source = 'SIMULATION' AND status IN ('PLAYING','AWAITING_EVIDENCE','UNDER_REVIEW','RESOLVED')
            AND preparation_deadline IS NOT NULL AND captain_a_ready AND captain_b_ready)
    ),
    ADD CONSTRAINT room_simulated_result CHECK (
        (status IN ('UNDER_REVIEW','RESOLVED') AND simulated_winner IS NOT NULL AND simulated_winner IN ('A','B'))
        OR (status NOT IN ('UNDER_REVIEW','RESOLVED') AND simulated_winner IS NULL)
    );
