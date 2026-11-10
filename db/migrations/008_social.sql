-- J'aime et commentaires entre membres d'une famille
CREATE TABLE IF NOT EXISTS media_likes (
    media_id   uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (media_id, user_id)
);

CREATE TABLE IF NOT EXISTS media_comments (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    media_id   uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body       text NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS media_comments_media_idx ON media_comments (media_id, created_at);
