-- Vidéo souvenir du mois, une par famille (owner_id = plus petit id des membres, cf. tasks.family_key).
CREATE TABLE IF NOT EXISTS memory_videos (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  month          date NOT NULL,                    -- 1er du mois
  status         text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'ready', 'empty', 'error')),
  media_ids      uuid[],
  cover_media_id uuid REFERENCES media(id) ON DELETE SET NULL,
  duration_s     real,
  error          text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_id, month)
);
