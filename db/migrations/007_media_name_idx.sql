-- Ré-import rapide d'un dossier NAS : « déjà importé ? » par nom + taille (tasks._import_file).
CREATE INDEX IF NOT EXISTS media_name_size_idx ON media (owner_id, original_name, size_bytes);
