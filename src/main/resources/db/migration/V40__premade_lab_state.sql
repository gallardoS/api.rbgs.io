-- Populated only by the guarded local matchmaking lab, separate from real accounts/seasons.
CREATE TABLE rbgs.premade_lab_state (
    id INTEGER PRIMARY KEY CHECK (id=1),
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id),
    generation UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
