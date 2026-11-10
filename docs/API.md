# Contrat d'API v1

Base : `https://<serveur>/api/v1`. Toutes les routes sauf `/auth/login` exigent `Authorization: Bearer <jwt>`.
Schéma OpenAPI généré : `GET /openapi.json`, UI interactive sur `/docs`. Implémentation : [server/api.py](../server/api.py).

| Méthode | Route | Rôle |
|---|---|---|
| POST | `/auth/login` | email + mot de passe → JWT (90 j) |
| POST | `/sync/check-hashes` | quels SHA-256 sont déjà sur le serveur (≤ 1000 par appel) |
| POST | `/uploads` | init (ou reprise) d'un upload résumable |
| GET | `/uploads/{id}` | offset actuel (après coupure réseau) |
| PUT | `/uploads/{id}` | envoi d'un bloc (`Content-Range`) |
| POST | `/uploads/{id}/complete` | finalise, déduplique, lance le pipeline |
| GET | `/media/timeline` | grille chronologique, pagination par curseur |
| GET | `/search/semantic?q=` | recherche CLIP en langage naturel |
| GET | `/media/{id}/thumb/{sm\|md}` | miniature WebP (cache immuable) |
| GET | `/media/{id}/original` | fichier original, `Range` supporté (seek vidéo) |

## Séquence de synchronisation (client Android)

```
1. POST /sync/check-hashes  {"hashes": ["<sha256 hex>", ...]}
   ← {"existing": {"<sha256>": "<media_id>", ...}}           → marquer "Synchronisé" en local
2. pour chaque hash absent :
   POST /uploads {"sha256","size","filename","mime_type","taken_at"?}
   ← {"status":"exists","media_id"}                           → fini
   ← {"status":"upload","upload_id","offset","chunk_max"}     → offset > 0 = reprise
3. tant que offset < size :
   PUT /uploads/{id}   Content-Range: bytes {offset}-{offset+n-1}/{size}   (corps binaire, n ≤ chunk_max)
   ← 200 {"offset": nouvel_offset}
   ← 409 {"detail":{"offset":X}}  → reprendre à X        ← 400 bloc incomplet → renvoyer le même bloc
4. POST /uploads/{id}/complete
   ← 202 {"status":"processing","media_id"}   ← 200 {"status":"exists","media_id"}
   ← 409 {"detail":{"offset":X}} upload incomplet   ← 410 {"status":"restart"} → recommencer en 2
```

Le serveur recalcule le SHA-256 avant l'entrée en bibliothèque ; en cas d'écart le média est supprimé
et réapparaîtra comme absent au prochain `check-hashes` (le client le renverra).
Une session non terminée expire 24 h après son dernier bloc.

## Formats

`POST /auth/login`
```json
{"email": "moi@example.com", "password": "…"}
→ {"access_token": "eyJ…", "token_type": "bearer", "expires_at": "2027-02-06T10:00:00Z"}
```

`POST /uploads`
```json
{"sha256": "9f86d0…(64 hex)", "size": 3481923, "filename": "PXL_20240714_193005.jpg",
 "mime_type": "image/jpeg", "taken_at": "2024-07-14T21:30:05+02:00"}
```
Contraintes : `mime_type` en `image/*` ou `video/*`, `size` ≤ `MAX_UPLOAD_BYTES` (50 Gio par défaut), bloc ≤ 64 Mio.

`GET /media/timeline?limit=200&cursor=<next_cursor>`
```json
{"items": [{"id": "…", "sha256": "…", "kind": "image", "mime_type": "image/jpeg",
            "taken_at": "2024-07-14T21:30:05Z", "width": 3000, "height": 4000, "duration_ms": null}],
 "next_cursor": "MjAyNC0w…"}          // null = fin
```
Tri `taken_at` décroissant. `taken_at` est l'heure locale de prise de vue encodée en UTC : grouper par jour/mois/année
côté client sur la valeur UTC telle quelle. `sha256` permet au client d'associer les éléments à ses fichiers locaux.

`GET /search/semantic?q=coucher de soleil à la plage&limit=60`
```json
{"items": [{ …mêmes champs que la timeline…, "score": 0.31 }]}
```
