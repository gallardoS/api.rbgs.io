CREATE TABLE rbgs.lobby_browser_presence (
    session_primary_id CHAR(36) PRIMARY KEY REFERENCES rbgs.spring_session(primary_id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES rbgs.accounts(id) ON DELETE CASCADE,
    seen_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX lobby_browser_presence_account ON rbgs.lobby_browser_presence (account_id, seen_at);

CREATE INDEX play_characters_forever_name ON rbgs.play_characters (region, lower(name))
    WHERE namespace IN ('profile-forever-eu', 'profile-forever-us');
