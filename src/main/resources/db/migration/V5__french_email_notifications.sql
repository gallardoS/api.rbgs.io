ALTER TABLE rbgs.email_subscriptions DROP CONSTRAINT email_subscriptions_language_check;
ALTER TABLE rbgs.email_subscriptions ADD CONSTRAINT email_subscriptions_language_check
    CHECK (language IN ('en', 'es', 'fr'));
