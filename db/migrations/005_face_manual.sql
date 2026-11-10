-- Visage corrigé à la main (réattribué ou retiré) : la détection automatique ne le touche plus.
ALTER TABLE faces ADD COLUMN IF NOT EXISTS manual boolean NOT NULL DEFAULT false;
