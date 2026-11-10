CREATE TABLE rbgs.preseason_bot_run (
    id integer PRIMARY KEY CHECK (id = 1),
    owner_id uuid NOT NULL REFERENCES rbgs.accounts(id),
    expires_at timestamptz NOT NULL,
    own_group_id uuid,
    rival_group_id uuid,
    host_ticket_id uuid REFERENCES rbgs.queue_tickets(id),
    CHECK ((own_group_id IS NULL) = (rival_group_id IS NULL))
);

ALTER TABLE rbgs.queue_tickets
    ADD COLUMN test_bot boolean NOT NULL DEFAULT false,
    DROP CONSTRAINT real_queue_versions,
    ADD CONSTRAINT real_queue_versions CHECK (
        (NOT test_bot OR (source = 'FOREVER' AND companion_device_id IS NULL))
        AND (source <> 'FOREVER' OR (
            season_version IS NOT NULL AND selection_version IS NOT NULL
            AND (test_bot OR companion_device_id IS NOT NULL)
        ))
    );
