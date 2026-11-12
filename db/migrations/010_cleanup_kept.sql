-- Photos décochées dans « Nettoyer la bibliothèque » : gardées volontairement, plus jamais proposées pour ce critère. Idempotent.
CREATE TABLE IF NOT EXISTS cleanup_kept (
  media_id   uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  kind       text NOT NULL CHECK (kind IN ('duplicates', 'blurry', 'dark', 'screenshots')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (media_id, kind)
);
