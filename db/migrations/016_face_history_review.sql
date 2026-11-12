-- Historique des corrections de visages (annulables), fusions réversibles, réponses « À vérifier ». Idempotent.
CREATE TABLE IF NOT EXISTS face_events (
  id          bigserial PRIMARY KEY,
  batch       uuid NOT NULL,                     -- une action utilisateur = un lot, annulable d'un coup
  kind        text NOT NULL CHECK (kind IN ('move', 'merge', 'review')),
  face_id     uuid REFERENCES faces(id) ON DELETE CASCADE,
  from_person uuid,
  to_person   uuid,
  from_manual boolean,
  user_id     uuid REFERENCES users(id) ON DELETE SET NULL,
  undone_at   timestamptz,
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS face_events_batch_idx ON face_events (batch);
-- Fusion : la personne absorbée est cachée (pas supprimée) pour pouvoir annuler
ALTER TABLE people ADD COLUMN IF NOT EXISTS merged_into uuid REFERENCES people(id) ON DELETE SET NULL;
-- « Non, ce n'est pas lui » : ne plus reposer la question
CREATE TABLE IF NOT EXISTS pet_rejects (
  pet_id   uuid NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
  media_id uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  PRIMARY KEY (pet_id, media_id)
);
