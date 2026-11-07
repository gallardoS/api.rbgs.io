ALTER TABLE rbgs.match_competitive_context ADD COLUMN rating_order BIGINT;

WITH ordered AS (
    SELECT match_id, row_number() OVER (PARTITION BY season_id, queue_type ORDER BY created_at, match_id) AS position
    FROM rbgs.match_competitive_context
)
UPDATE rbgs.match_competitive_context c SET rating_order = ordered.position
FROM ordered WHERE c.match_id = ordered.match_id;

ALTER TABLE rbgs.match_competitive_context
    ALTER COLUMN rating_order SET NOT NULL,
    ADD CHECK (rating_order > 0),
    ADD UNIQUE (season_id, queue_type, rating_order);

CREATE TABLE rbgs.rating_queue_sequence (
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id) ON DELETE CASCADE,
    queue_type VARCHAR(16) NOT NULL CHECK (queue_type IN ('SOLO', 'PREMADE')),
    last_position BIGINT NOT NULL CHECK (last_position > 0),
    PRIMARY KEY (season_id, queue_type)
);

INSERT INTO rbgs.rating_queue_sequence
SELECT season_id, queue_type, max(rating_order)
FROM rbgs.match_competitive_context GROUP BY season_id, queue_type;

CREATE TABLE rbgs.rating_result_revisions (
    replay_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    match_id UUID NOT NULL REFERENCES rbgs.match_competitive_context(match_id) ON DELETE CASCADE,
    result_version BIGINT NOT NULL CHECK (result_version >= 0),
    outcome VARCHAR(8) NOT NULL CHECK (outcome IN ('A', 'B', 'DRAW', 'UNRATED')),
    algorithm VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (match_id, result_version)
);

CREATE TABLE rbgs.rating_movements (
    replay_match_id UUID NOT NULL,
    replay_result_version BIGINT NOT NULL,
    match_id UUID NOT NULL REFERENCES rbgs.match_competitive_context(match_id) ON DELETE CASCADE,
    subject_id UUID NOT NULL,
    team VARCHAR(1) NOT NULL CHECK (team IN ('A', 'B')),
    mmr_before INTEGER NOT NULL CHECK (mmr_before >= 0),
    mmr_after INTEGER NOT NULL CHECK (mmr_after >= 0),
    visible_before INTEGER NOT NULL CHECK (visible_before >= 0),
    visible_after INTEGER NOT NULL CHECK (visible_after >= 0),
    team_mmr NUMERIC(12,1) NOT NULL,
    opponent_mmr NUMERIC(12,1) NOT NULL,
    PRIMARY KEY (replay_match_id, replay_result_version, match_id, subject_id),
    FOREIGN KEY (replay_match_id, replay_result_version)
        REFERENCES rbgs.rating_result_revisions(match_id, result_version) ON DELETE CASCADE
);
