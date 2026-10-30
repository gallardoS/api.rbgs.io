-- Existing live invitations must not retain their previous thirty-minute lifetime.
-- Without an original creation timestamp, cap remaining validity at one minute from upgrade.
UPDATE rbgs.group_invitations SET expires_at = statement_timestamp() + INTERVAL '60 seconds'
    WHERE expires_at > statement_timestamp() + INTERVAL '60 seconds';
