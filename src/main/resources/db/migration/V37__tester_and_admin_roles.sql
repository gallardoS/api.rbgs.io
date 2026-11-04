ALTER TABLE rbgs.accounts DROP CONSTRAINT accounts_role_valid;
ALTER TABLE rbgs.accounts ADD CONSTRAINT accounts_role_valid
    CHECK (account_role IN ('USER', 'TESTER', 'MODERATOR', 'ADMIN'));
