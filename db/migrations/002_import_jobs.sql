-- Imports lancés depuis la page web (dossier du NAS -> bibliothèque). Idempotent.
CREATE TABLE IF NOT EXISTS import_jobs (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  path        text NOT NULL,             -- relatif à IMPORT_ROOT
  status      text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'error')),
  found       int  NOT NULL DEFAULT 0,
  imported    int  NOT NULL DEFAULT 0,   -- envoyés dans le pipeline (ingest)
  skipped     int  NOT NULL DEFAULT 0,   -- déjà dans la bibliothèque (même SHA-256)
  failed      int  NOT NULL DEFAULT 0,
  error       text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  started_at  timestamptz,
  finished_at timestamptz
);
CREATE INDEX IF NOT EXISTS import_jobs_owner_idx ON import_jobs (owner_id, created_at DESC);
