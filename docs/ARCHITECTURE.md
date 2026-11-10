# Architecture — Photothèque auto-hébergée

## 1. Composants

```
  Android (Kotlin/Compose)        PWA (SvelteKit)
          │  HTTPS + JWT                │
          └──────────────┬──────────────┘
                         ▼
                 API (FastAPI) ─────────────────► PostgreSQL 16 + pgvector
                  │        │                        ▲  (métadonnées, vecteurs,
     chunks       │        │ enqueue                │   tags, visages)
                  ▼        ▼                        │
     NAS:/photos/.quarantine   Redis (broker Celery)│
                                 │                  │
           ┌─────────────────────┼──────────────────┤
           ▼                     ▼                  ▼
   worker-media (Q media)  worker-ml (Q ml)   worker-vlm (Q vlm, concurrency=1)
   ingest, EXIF, geocoding  CLIP, visages      Gemini Flash / Ollama
   miniatures, poster vidéo (CPU ou GPU)       rate_limit + backoff
           │                                        ▲
           ▼                                 beat : sweep_vlm (horaire)
   NAS:/photos/{owner}/YYYY/MM/DD/{sha256}.{ext}    (originaux)
   SSD:/thumbs/{id[:2]}/{id}/{sm,md}.webp            (miniatures, accès rapide)
```

Choix structurants :

- **Python partout** (FastAPI + Celery) : CLIP et InsightFace sont natifs Python, l'API réutilise `server/ml.py` pour encoder le texte des recherches avec *le même* modèle.
- **Trois files Celery** : `media` (I/O, parallèle), `ml` (modèles ~1 Go RAM par process, peu de concurrence), `vlm` (un seul process : le `rate_limit` Celery est un token bucket *par worker*, donc global seulement avec un unique consommateur).
- **Quarantaine sur le même système de fichiers que `/photos`** : le passage quarantaine → bibliothèque est un `rename` atomique, jamais de fichier à moitié copié.
- **Miniatures hors NAS** (SSD local) : la grille charge des centaines de WebP par écran ; le NAS ne sert que les originaux et la vidéo.
- **`{owner}` dans le chemin** : deux utilisateurs avec le même fichier n'ont pas à partager (et compter les références d') un même fichier disque.
- **CLIP multilingue** (`xlm-roberta-base-ViT-B-32`) par défaut : ViT-B/32 « classique » est anglophone, « coucher de soleil à la plage » y donne de mauvais résultats.

## 2. Pipeline d'un fichier entrant

```
[Client]   SHA-256 local en streaming
   │ POST /api/v1/sync/check-hashes        → déjà présent ? fin
   │ POST /api/v1/uploads                  → upload_id (upload_sessions)
   │ PUT  /api/v1/uploads/{id}  (Content-Range, reprise possible)
   │        → /photos/.quarantine/{upload_id}.part
   │ POST /api/v1/uploads/{id}/complete
   ▼
[API]      received_bytes == size ?
   │ INSERT media (status=quarantined, taken_at=DATE_TAKEN client)
   │   ON CONFLICT (owner_id, sha256) DO NOTHING
   │     └─ conflit = doublon : suppression du .part, renvoie le média existant (200)
   │ rename .part → .quarantine/{media_id}
   │ ingest.delay(media_id)                → 202
   ▼
[worker-media : ingest]
   0. SHA-256 serveur == SHA-256 annoncé ?  sinon suppression fichier + ligne media (le client renverra)
   1. exiftool -json -n      → date, GPS, appareil, dimensions, orientation, durée
   2. reverse geocoding offline (GeoNames cities1000)
   3. UPDATE media (storage_path…) puis rename → /photos/{owner}/YYYY/MM/DD/{sha256}.{ext}
   4. miniatures WebP sm (320 px) / md (1440 px), auto-rotation EXIF ; vidéo : poster ffmpeg
   5. status=ready → clip_embed.delay, detect_faces.delay
   ▼
[worker-ml]   CLIP → embeddings     InsightFace → faces + rattachement à une personne
[beat]        sweep_vlm : N médias sans description par heure → queue vlm
              housekeeping (15 min) : purge des uploads expirés, relance des ingests bloqués
[worker-vlm]  Gemini Flash / Ollama → description, OCR, tags (JSON structuré)
   ▼
Index : HNSW pgvector (CLIP, visages) · tsvector (description + OCR) · tags
```

Chaque étape est idempotente (relivraison Celery avec `acks_late`) : on peut relancer `ingest`, `clip_embed`, `detect_faces` ou `describe` sur un média sans effet de bord.

## 3. Requêtes clés

Timeline (pagination par curseur, index `media_timeline_idx`) :

```sql
SELECT id, taken_at, kind, width, height
FROM media
WHERE owner_id = $1 AND status = 'ready' AND deleted_at IS NULL
  AND (taken_at, id) < ($2, $3)          -- curseur = dernier élément reçu
ORDER BY taken_at DESC, id DESC
LIMIT 200;
```

Recherche sémantique (`$2` = `ml.embed_text(q)` calculé par l'API) :

```sql
SET hnsw.iterative_scan = relaxed_order;  -- pgvector ≥ 0.8 : le filtre owner ne vide pas le top-k
SELECT m.id, 1 - (e.embedding <=> $2) AS score
FROM embeddings e JOIN media m ON m.id = e.media_id
WHERE m.owner_id = $1 AND m.deleted_at IS NULL AND e.model = $3
ORDER BY e.embedding <=> $2
LIMIT 60;
```

À fusionner (RRF) avec `search_tsv @@ websearch_to_tsquery('simple', q)` pour les correspondances exactes de texte OCR (« facture EDF », plaque, panneau).

## 4. Lancer les workers

Un seul process pour toutes les files (VM Freebox 2 Go : modèles chargés une fois, rate-limit Gemini global) :

```bash
celery -A tasks worker -B -Q query,media,ml,vlm --pool threads -c 3
```

La recherche ne charge pas CLIP dans l'API : elle envoie la requête au worker (tâche `embed_query`, file `query`)
et attend le vecteur via le backend de résultats Redis.

Sur une machine plus grosse, on peut séparer `-Q media` (prefork, -c 4), `-Q query,ml` et `-Q vlm -c 1`
(la file vlm doit garder un consommateur unique).
