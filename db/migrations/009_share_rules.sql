-- Partage limité : un membre « restreint » ne voit que ses propres médias + ceux qui correspondent à une règle.
-- L'administrateur (créateur de la famille) gère les membres et les règles.
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin boolean NOT NULL DEFAULT true;  -- comptes créés en ligne de commande
ALTER TABLE users ADD COLUMN IF NOT EXISTS restricted boolean NOT NULL DEFAULT false;
-- Membres déjà ajoutés depuis l'app : seul le plus ancien de chaque famille reste administrateur
UPDATE users u SET is_admin = false
WHERE u.family_id IS NOT NULL
  AND u.id <> (SELECT u2.id FROM users u2 WHERE u2.family_id = u.family_id ORDER BY u2.created_at LIMIT 1);

-- Une règle = (dates) ET (une des personnes OU un des albums ; rien = toutes les photos de la période)
CREATE TABLE IF NOT EXISTS share_rules (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    viewer_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    date_from  date,
    date_to    date,
    people     uuid[] NOT NULL DEFAULT '{}',
    albums     uuid[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS share_rules_viewer_idx ON share_rules (viewer_id);

-- Le média (de la famille du membre) correspond-il à une de ses règles ?
CREATE OR REPLACE FUNCTION can_view(viewer uuid, mid uuid, owner uuid, taken timestamptz) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (
    SELECT 1 FROM share_rules r
    JOIN users v ON v.id = r.viewer_id
    JOIN users o ON o.id = owner AND o.family_id = v.family_id
    WHERE r.viewer_id = viewer
      AND (r.date_from IS NULL OR taken >= r.date_from)
      AND (r.date_to IS NULL OR taken < r.date_to + 1)
      AND ((cardinality(r.people) = 0 AND cardinality(r.albums) = 0)
           OR EXISTS (SELECT 1 FROM faces f WHERE f.media_id = mid AND f.person_id = ANY(r.people))
           OR EXISTS (SELECT 1 FROM album_media am WHERE am.media_id = mid AND am.album_id = ANY(r.albums))))
$$;
