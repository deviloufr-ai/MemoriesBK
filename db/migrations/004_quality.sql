-- Qualité d'image pour le nettoyage des photos ratées. Idempotent.
ALTER TABLE media ADD COLUMN IF NOT EXISTS sharpness real;   -- variance du laplacien (bas = flou)
ALTER TABLE media ADD COLUMN IF NOT EXISTS brightness real;  -- luminance moyenne 0..255
CREATE INDEX IF NOT EXISTS media_trash_idx ON media (owner_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;
