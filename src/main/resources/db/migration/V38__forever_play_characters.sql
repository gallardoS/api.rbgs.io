ALTER TABLE rbgs.play_characters DROP CONSTRAINT play_characters_namespace_check;
ALTER TABLE rbgs.play_characters ADD CONSTRAINT play_characters_namespace_check CHECK (namespace IN (
    'profile-classic1x-eu', 'profile-classic-eu', 'profile-classicann-eu',
    'profile-classic1x-us', 'profile-classic-us', 'profile-classicann-us',
    'profile-forever-eu', 'profile-forever-us'
));
