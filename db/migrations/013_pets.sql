-- Animaux nommés : quelques photos d'exemple ; les autres sont retrouvées par ressemblance CLIP. Idempotent.
CREATE TABLE IF NOT EXISTS pets (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  examples   uuid[] NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pets_owner_idx ON pets (owner_id);
