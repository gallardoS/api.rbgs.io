-- Rotate enabled legacy codes without changing groups, memberships or preparation revisions.
DO $$
DECLARE
    lobby RECORD;
    alphabet CONSTANT TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    candidate TEXT;
    candidate_hash TEXT;
    position INTEGER;
BEGIN
    PERFORM pg_advisory_xact_lock(82479200608);
    FOR lobby IN
        SELECT id FROM rbgs.premade_groups
        WHERE join_code IS NOT NULL AND join_code !~ '^[A-HJ-NP-Z2-9]{8}$'
        ORDER BY id FOR UPDATE
    LOOP
        LOOP
            candidate := '';
            FOR position IN 1..8 LOOP
                candidate := candidate || substr(alphabet, 1 + get_byte(uuid_send(gen_random_uuid()), 0) % 32, 1);
            END LOOP;
            candidate_hash := encode(sha256(convert_to(candidate, 'UTF8')), 'hex');
            EXIT WHEN NOT EXISTS (SELECT 1 FROM rbgs.premade_groups WHERE join_code_hash = candidate_hash);
        END LOOP;
        UPDATE rbgs.premade_groups SET join_code = candidate, join_code_hash = candidate_hash WHERE id = lobby.id;
    END LOOP;
END $$;

ALTER TABLE rbgs.premade_groups ALTER COLUMN join_code TYPE VARCHAR(8);
ALTER TABLE rbgs.premade_groups ADD CONSTRAINT lobby_code_format
    CHECK (join_code IS NULL OR join_code ~ '^[A-HJ-NP-Z2-9]{8}$');
