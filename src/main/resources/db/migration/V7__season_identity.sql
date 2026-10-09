CREATE TABLE rbgs.seasons (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('BETA', 'PUBLIC')),
    rating_subject_type VARCHAR(16) NOT NULL CHECK (rating_subject_type IN ('ACCOUNT', 'CHARACTER')),
    started_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

-- A direct SQL update cannot reopen a started season or change its rating ownership.
CREATE FUNCTION rbgs.protect_started_season_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.started_at IS NOT NULL AND (
        NEW.started_at IS DISTINCT FROM OLD.started_at OR
        NEW.rating_subject_type IS DISTINCT FROM OLD.rating_subject_type OR
        NEW.kind IS DISTINCT FROM OLD.kind
    ) THEN
        RAISE EXCEPTION 'Started season identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER seasons_protect_started_identity
BEFORE UPDATE ON rbgs.seasons
FOR EACH ROW EXECUTE FUNCTION rbgs.protect_started_season_identity();
