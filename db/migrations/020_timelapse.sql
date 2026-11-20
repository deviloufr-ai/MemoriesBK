-- Timelapse d'une personne : même table que les vidéos du mois (liste, lecture, statut), une par personne et famille.
ALTER TABLE memory_videos ADD COLUMN IF NOT EXISTS person_id uuid REFERENCES people(id) ON DELETE CASCADE;
ALTER TABLE memory_videos DROP CONSTRAINT IF EXISTS memory_videos_owner_id_month_key;
CREATE UNIQUE INDEX IF NOT EXISTS memory_videos_month_uq ON memory_videos (owner_id, month) WHERE person_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS memory_videos_person_uq ON memory_videos (owner_id, person_id) WHERE person_id IS NOT NULL;
