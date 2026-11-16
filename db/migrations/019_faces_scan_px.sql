-- Définition de l'image où les visages ont été cherchés : 1440 (miniature md), ou FACE_SOURCE_PX (original réduit,
-- serveur 30 Go). NULL = avant ce suivi (miniature). Le serveur ré-analyse, sans rien supprimer, ce qui est en dessous
-- de son réglage (tasks._faces_backfill). Idempotent.
ALTER TABLE media ADD COLUMN IF NOT EXISTS faces_scan_px int;
CREATE INDEX IF NOT EXISTS media_faces_rescan_idx ON media (taken_at DESC)
  WHERE kind = 'image' AND status = 'ready' AND deleted_at IS NULL;
