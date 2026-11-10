-- Schéma PostgreSQL 16 + pgvector >= 0.8 (image Docker pgvector/pgvector:pg16)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         text NOT NULL,
  password_hash text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_key ON users (lower(email));

-- Upload résumable : une ligne par fichier en cours de transfert
CREATE TABLE upload_sessions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sha256          bytea NOT NULL CHECK (octet_length(sha256) = 32),
  size_bytes      bigint NOT NULL CHECK (size_bytes > 0),
  received_bytes  bigint NOT NULL DEFAULT 0,
  original_name   text NOT NULL,
  mime_type       text NOT NULL,
  client_taken_at timestamptz,          -- MediaStore DATE_TAKEN, repli si pas d'EXIF
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL DEFAULT now() + interval '24 hours'
);
CREATE INDEX upload_sessions_expires_idx ON upload_sessions (expires_at);

CREATE TABLE media (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sha256           bytea NOT NULL CHECK (octet_length(sha256) = 32),
  kind             text NOT NULL CHECK (kind IN ('image', 'video')),
  mime_type        text NOT NULL,
  original_name    text NOT NULL,
  size_bytes       bigint NOT NULL,
  storage_path     text,                -- relatif à PHOTOS_DIR ; NULL tant qu'en quarantaine
  status           text NOT NULL DEFAULT 'quarantined'
                   CHECK (status IN ('quarantined', 'ready', 'error')),
  error            text,
  taken_at         timestamptz,         -- heure locale de prise de vue (cf. server/metadata.py)
  width            int,
  height           int,
  duration_ms      int,
  camera_make      text,
  camera_model     text,
  latitude         double precision,
  longitude        double precision,
  city             text,
  admin1           text,
  country_code     char(2),
  exif             jsonb,
  -- Analyse VLM (Gemini / Ollama)
  description      text,
  ocr_text         text,
  vlm_attempted_at timestamptz,
  vlm_done_at      timestamptz,
  search_tsv       tsvector GENERATED ALWAYS AS (
                     to_tsvector('simple', coalesce(description, '') || ' ' || coalesce(ocr_text, ''))
                   ) STORED,
  created_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,         -- corbeille
  UNIQUE (owner_id, sha256)             -- déduplication, arbitre les uploads concurrents
);
CREATE INDEX media_timeline_idx ON media (owner_id, taken_at DESC, id DESC)
  WHERE status = 'ready' AND deleted_at IS NULL;
CREATE INDEX media_search_tsv_idx ON media USING gin (search_tsv);
CREATE INDEX media_geo_idx ON media (owner_id, latitude, longitude) WHERE latitude IS NOT NULL;
CREATE INDEX media_vlm_todo_idx ON media (taken_at DESC)
  WHERE status = 'ready' AND deleted_at IS NULL AND vlm_done_at IS NULL;

-- Vecteurs CLIP. Changer de modèle = nouvelles lignes avec un autre `model`
-- (une autre dimension que 512 impose une autre table/colonne).
CREATE TABLE embeddings (
  media_id  uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  model     text NOT NULL,              -- ex. 'xlm-roberta-base-ViT-B-32/laion5b_s13b_b90k'
  embedding vector(512) NOT NULL,       -- normalisé L2
  PRIMARY KEY (media_id, model)
);
CREATE INDEX embeddings_hnsw_idx ON embeddings USING hnsw (embedding vector_cosine_ops);

CREATE TABLE people (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       text,                      -- NULL = pas encore nommée
  hidden     boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX people_owner_idx ON people (owner_id);

CREATE TABLE faces (
  id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  media_id  uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  owner_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- dénormalisé pour le kNN
  person_id uuid REFERENCES people(id) ON DELETE SET NULL,
  bbox      real[] NOT NULL CHECK (array_length(bbox, 1) = 4),     -- x1,y1,x2,y2 normalisés 0..1
  det_score real NOT NULL,
  embedding vector(512) NOT NULL        -- ArcFace (InsightFace buffalo_l), normalisé
);
CREATE INDEX faces_media_idx ON faces (media_id);
CREATE INDEX faces_person_idx ON faces (person_id);
CREATE INDEX faces_hnsw_idx ON faces USING hnsw (embedding vector_cosine_ops);

CREATE TABLE albums (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title          text NOT NULL,
  cover_media_id uuid REFERENCES media(id) ON DELETE SET NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX albums_owner_idx ON albums (owner_id);

CREATE TABLE album_media (
  album_id uuid NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
  media_id uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  added_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (album_id, media_id)
);
CREATE INDEX album_media_media_idx ON album_media (media_id);

CREATE TABLE tags (
  id   int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name text NOT NULL UNIQUE             -- minuscules, normalisé par le worker
);

CREATE TABLE media_tags (
  media_id uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  tag_id   int  NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  source   text NOT NULL CHECK (source IN ('vlm', 'user')),
  PRIMARY KEY (media_id, tag_id)
);
CREATE INDEX media_tags_tag_idx ON media_tags (tag_id);
