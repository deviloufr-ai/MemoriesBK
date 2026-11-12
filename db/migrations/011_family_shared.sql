-- Onglet « Famille » : photos que les membres choisissent de partager (chacun peut en ajouter). Idempotent.
CREATE TABLE IF NOT EXISTS family_shared (
  media_id   uuid PRIMARY KEY REFERENCES media(id) ON DELETE CASCADE,
  shared_by  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS family_shared_created_idx ON family_shared (created_at DESC);
