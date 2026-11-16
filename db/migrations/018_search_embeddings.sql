-- Vecteurs du modèle de recherche plus gros (SEARCH_CLIP_ARCH, serveur 30 Go) : 1024 dimensions, d'où une table
-- à part (embeddings est en vector(512)). Ne sert qu'à la recherche par texte ; la Freebox l'ignore. Idempotent.
CREATE TABLE IF NOT EXISTS search_embeddings (
  media_id  uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  model     text NOT NULL,              -- ex. 'xlm-roberta-large-ViT-H-14/frozen_laion5b_s13b_b90k'
  embedding vector(1024) NOT NULL,      -- normalisé L2
  PRIMARY KEY (media_id, model)
);
CREATE INDEX IF NOT EXISTS search_embeddings_hnsw_idx ON search_embeddings USING hnsw (embedding vector_cosine_ops);
