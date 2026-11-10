-- Bibliothèque familiale, catégories IA, retouches. Idempotent.

-- Famille : les utilisateurs de même family_id partagent la bibliothèque (NULL = seul)
ALTER TABLE users ADD COLUMN IF NOT EXISTS family_id uuid;
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS role text;   -- papa, maman, enfant, grand-parent, autre
CREATE INDEX IF NOT EXISTS users_family_idx ON users (family_id);

-- Catégories calculées localement par CLIP : nouvelle source de tag
ALTER TABLE media_tags DROP CONSTRAINT IF EXISTS media_tags_source_check;
ALTER TABLE media_tags ADD CONSTRAINT media_tags_source_check CHECK (source IN ('vlm', 'user', 'clip'));

-- Retouche : la version améliorée est un nouveau média qui pointe vers l'original
ALTER TABLE media ADD COLUMN IF NOT EXISTS edited_from uuid REFERENCES media(id) ON DELETE SET NULL;
