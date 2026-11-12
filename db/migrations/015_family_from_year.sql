-- Famille : personne ou animal partagé seulement à partir d'une année (NULL = toutes). Idempotent.
ALTER TABLE family_share_people ADD COLUMN IF NOT EXISTS from_year int CHECK (from_year BETWEEN 1900 AND 2100);
ALTER TABLE family_share_pets ADD COLUMN IF NOT EXISTS from_year int CHECK (from_year BETWEEN 1900 AND 2100);
