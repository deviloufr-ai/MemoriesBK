-- « Retirer de Famille » sur n'importe quelle photo de l'onglet, même arrivée par une personne ou un animal partagé :
-- une ligne posée par un membre la retire pour toute la famille (et pour les membres restreints). Réversible depuis
-- Gérer › Retirées de Famille ; un nouveau partage explicite la fait revenir. Idempotent.
CREATE TABLE IF NOT EXISTS family_hidden (
  media_id   uuid NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  hidden_by  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (media_id, hidden_by)
);
