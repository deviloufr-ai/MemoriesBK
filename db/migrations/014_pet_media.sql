-- Photos retrouvées pour chaque animal (recalculées au nommage et par tasks.housekeeping), et animaux partagés dans Famille. Idempotent.
CREATE TABLE IF NOT EXISTS pet_media (
  pet_id   uuid NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
  media_id uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  PRIMARY KEY (pet_id, media_id)
);
CREATE TABLE IF NOT EXISTS family_share_pets (
  pet_id     uuid PRIMARY KEY REFERENCES pets(id) ON DELETE CASCADE,
  added_by   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);
