-- Persistent initial competitive state; result movements/replay remain feature 12.
CREATE TABLE rbgs.competitive_ratings (
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id) ON DELETE CASCADE,
    queue_type VARCHAR(16) NOT NULL CHECK (queue_type IN ('SOLO','PREMADE')),
    subject_type VARCHAR(16) NOT NULL CHECK (subject_type IN ('ACCOUNT','CHARACTER')),
    subject_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id) ON DELETE CASCADE,
    character_id UUID,
    mmr INTEGER NOT NULL DEFAULT 1500 CHECK (mmr >= 0),
    visible_rating INTEGER NOT NULL DEFAULT 0 CHECK (visible_rating >= 0),
    games_played INTEGER NOT NULL DEFAULT 0 CHECK (games_played >= 0),
    captain_assignments INTEGER NOT NULL DEFAULT 0 CHECK (captain_assignments >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (season_id,queue_type,subject_type,subject_id),
    FOREIGN KEY (account_id,character_id) REFERENCES rbgs.character_identities(account_id,id) ON DELETE CASCADE,
    CHECK ((subject_type='ACCOUNT' AND subject_id=account_id AND character_id IS NULL)
        OR (subject_type='CHARACTER' AND subject_id=character_id AND character_id IS NOT NULL))
);
-- Existing valid selections receive the specified initial state, never an invented match history.
INSERT INTO rbgs.competitive_ratings(season_id,queue_type,subject_type,subject_id,account_id,character_id)
SELECT s.season_id,q.queue_type,s.rating_subject_type,
       CASE WHEN s.rating_subject_type='ACCOUNT' THEN s.account_id ELSE s.character_id END,
       s.account_id,CASE WHEN s.rating_subject_type='CHARACTER' THEN s.character_id END
FROM rbgs.season_selections s JOIN rbgs.seasons r ON r.id=s.season_id AND r.rating_subject_type=s.rating_subject_type
JOIN rbgs.accounts a ON a.id=s.account_id
CROSS JOIN (VALUES ('SOLO'),('PREMADE')) AS q(queue_type)
WHERE a.provider_issuer NOT IN ('https://soloq-lab.invalid','https://premade-lab.invalid');

ALTER TABLE rbgs.premade_queue_entries ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SIMULATION'
    CHECK (source IN ('SIMULATION','FOREVER'));
CREATE INDEX premade_waiting_source ON rbgs.premade_queue_entries(source,entered_at) WHERE state='QUEUED';

CREATE TABLE rbgs.match_competitive_context (
    match_id UUID PRIMARY KEY REFERENCES rbgs.matches(id) ON DELETE CASCADE,
    room_id UUID UNIQUE NOT NULL REFERENCES rbgs.match_rooms(id),
    season_id UUID NOT NULL REFERENCES rbgs.seasons(id),
    queue_type VARCHAR(16) NOT NULL CHECK (queue_type IN ('SOLO','PREMADE')),
    snapshot TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
