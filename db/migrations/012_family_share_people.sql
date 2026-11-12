-- Onglet « Famille » par visage : toutes les photos (actuelles et futures) d'une personne y apparaissent. Idempotent.
CREATE TABLE IF NOT EXISTS family_share_people (
  person_id  uuid PRIMARY KEY REFERENCES people(id) ON DELETE CASCADE,
  added_by   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);
