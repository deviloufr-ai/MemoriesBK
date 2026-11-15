-- Photos masquées : retirées de la chronologie, des souvenirs et des récents, toujours dans albums, personnes,
-- recherche. Réversible (écran « Photos masquées »). Idempotent.
ALTER TABLE media ADD COLUMN IF NOT EXISTS hidden_at timestamptz;
CREATE INDEX IF NOT EXISTS media_hidden_idx ON media (owner_id, hidden_at DESC) WHERE hidden_at IS NOT NULL;
