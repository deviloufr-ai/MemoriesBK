"""API REST mobile/web. Lancement : uvicorn api:app --host 0.0.0.0 --port 8080
Créer un compte : python api.py create-user moi@example.com
Contrat détaillé : docs/API.md (OpenAPI interactif sur /docs).
"""
import mimetypes
import os
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

import celery.exceptions
import jwt
import numpy as np
import psycopg
import redis
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from pgvector.psycopg import register_vector
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool
from pydantic import BaseModel, Field

from metadata import media_mime, safe_join, visible_dir
from ml import CLIP_MODEL_ID  # constante seulement : importer ml ne charge aucun modèle
from tasks import (DSN, REDIS_URL, IMPORT_EXCLUDE, IMPORT_ROOT, PHOTOS_DIR, QUARANTINE_DIR, embed_query, import_folder, ingest,
                   part_path, thumb_path, MEMORY_VIDEO_DIR, queue_memory_video)
from tasks import enhance as enhance_task
from util import decode_cursor, encode_cursor, hash_password, parse_content_range, verify_password

JWT_SECRET = os.environ["JWT_SECRET"]
TOKEN_TTL = timedelta(days=int(os.environ.get("TOKEN_TTL_DAYS", "90")))
CHUNK_MAX = 64 * 2**20
MAX_UPLOAD = int(os.environ.get("MAX_UPLOAD_BYTES", str(50 * 2**30)))

pool = ConnectionPool(DSN, kwargs={"autocommit": True, "row_factory": dict_row},
                      configure=register_vector, max_size=10, open=True)
QUARANTINE_DIR.mkdir(parents=True, exist_ok=True)
app = FastAPI(title="MemoriesBK API", version="1")

# ---------- Auth ----------
# shortcut: un seul jeton longue durée, pas de refresh/révocation ; ajouter une table de sessions si besoin.


def current_user(request: Request,
                 cred: Annotated[HTTPAuthorizationCredentials | None, Depends(HTTPBearer(auto_error=False))]) -> UUID:
    # Le navigateur ne peut pas ajouter d'en-tête aux <img>/<video> : cookie accepté, mais en lecture seule (GET),
    # et SameSite=Strict côté web => pas de requête authentifiée déclenchée par un autre site.
    token = cred.credentials if cred else (request.cookies.get("mbk") if request.method == "GET" else None)
    if not token:
        raise HTTPException(401, "authentification requise")
    try:
        return UUID(jwt.decode(token, JWT_SECRET, algorithms=["HS256"])["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        raise HTTPException(401, "jeton invalide ou expiré")


User = Annotated[UUID, Depends(current_user)]
Sha256 = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class Family(list):
    """Propriétaires dont l'utilisateur voit (et peut modifier) les médias.

    Membre restreint : lui seul ; les médias partagés par règle passent par ready()/_owned(shared=True), en lecture."""
    uid: UUID
    restricted: bool = False
    admin: bool = False


def current_family(uid: User) -> Family:
    """Membres de la famille de l'utilisateur (lui seul s'il n'en a pas) : la bibliothèque leur est commune."""
    with pool.connection() as c:
        me = c.execute("SELECT is_admin, restricted FROM users WHERE id = %s", (uid,)).fetchone()
        if me is None:
            raise HTTPException(401, "compte supprimé")
        rows = [] if me["restricted"] else c.execute(
            "SELECT id FROM users WHERE family_id = (SELECT family_id FROM users WHERE id = %s) AND id <> %s",
            (uid, uid)).fetchall()
    fam = Family([uid, *(r["id"] for r in rows)])
    fam.uid, fam.restricted, fam.admin = uid, me["restricted"], me["is_admin"]
    return fam


Fam = Annotated[Family, Depends(current_family)]


def ready(fam: Family, shared: bool = True, m: str = "m") -> str:
    """Filtre SQL « médias visibles » ; paramètre %(fam)s requis. shared=False : écriture (pas les médias partagés)."""
    own = f"{m}.owner_id = ANY(%(fam)s)"
    if shared and fam.restricted:  # fam.uid : UUID issu du jeton, sûr à insérer dans la requête
        own = f"({own} OR can_view('{fam.uid}'::uuid, {m}.id, {m}.owner_id, {m}.taken_at))"
    return f"{own} AND {m}.status = 'ready' AND {m}.deleted_at IS NULL"


def require_admin(fam: Family) -> None:
    if not fam.admin:
        raise HTTPException(403, "réservé à l'administrateur")


def require_unrestricted(fam: Family) -> None:
    if fam.restricted:
        raise HTTPException(403, "non disponible avec un accès limité")
ROLES = ("papa", "maman", "enfant", "grand-parent", "autre")


@app.get("/api/v1/family")
def get_family(uid: User):
    with pool.connection() as c:
        rows = c.execute(
            "SELECT id, email, display_name, role, is_admin, restricted, (id = %(u)s) AS me FROM users WHERE id = %(u)s "
            "OR family_id = (SELECT family_id FROM users WHERE id = %(u)s) ORDER BY created_at", {"u": uid}).fetchall()
    return {"members": rows, "roles": ROLES, "gemini": bool(os.environ.get("GEMINI_API_KEY"))}


class MemberCreate(BaseModel):
    email: str = Field(min_length=3, max_length=254, pattern=r"^\S+$")  # identifiant : prénom, pseudo ou e-mail
    password: str = Field(min_length=10, max_length=256)
    display_name: str = Field(min_length=1, max_length=50)
    role: str = Field(pattern="^(" + "|".join(ROLES) + ")$")


@app.post("/api/v1/family/members", status_code=201)
def add_member(body: MemberCreate, uid: User, fam: Fam):
    """Crée le compte d'un membre ; il partage la bibliothèque et se connecte avec son propre téléphone."""
    require_admin(fam)
    with pool.connection() as c, c.transaction():
        fam_id = c.execute("UPDATE users SET family_id = coalesce(family_id, gen_random_uuid()) WHERE id = %s "
                           "RETURNING family_id", (uid,)).fetchone()["family_id"]
        try:
            row = c.execute("INSERT INTO users (email, password_hash, family_id, display_name, role, is_admin) "
                            "VALUES (%s, %s, %s, %s, %s, false) RETURNING id",
                            (body.email.strip(), hash_password(body.password), fam_id, body.display_name.strip(),
                             body.role)).fetchone()
        except psycopg.errors.UniqueViolation:
            raise HTTPException(409, "cet identifiant est déjà pris")
    return {"id": str(row["id"])}


class MemberPatch(BaseModel):
    display_name: str | None = Field(None, min_length=1, max_length=50)
    role: str | None = Field(None, pattern="^(" + "|".join(ROLES) + ")$")


@app.patch("/api/v1/family/members/{member_id}")
def patch_member(member_id: UUID, body: MemberPatch, fam: Fam):
    fields = body.model_dump(exclude_none=True)
    if not fields:
        raise HTTPException(400, "rien à modifier")
    if member_id not in fam:
        raise HTTPException(404, "membre introuvable")
    sets = ", ".join(f"{k} = %({k})s" for k in fields)  # clés du modèle pydantic, pas de l'entrée brute
    with pool.connection() as c:
        c.execute(f"UPDATE users SET {sets} WHERE id = %(id)s", {**fields, "id": member_id})
    return {"id": str(member_id)}


class ShareRule(BaseModel):
    date_from: date | None = None
    date_to: date | None = None
    people: list[UUID] = Field(default_factory=list, max_length=50)
    albums: list[UUID] = Field(default_factory=list, max_length=50)


class Sharing(BaseModel):
    restricted: bool
    rules: list[ShareRule] = Field(default_factory=list, max_length=20)


def _member(fam: Family, member_id: UUID) -> None:
    require_admin(fam)
    if member_id not in fam or member_id == fam.uid:
        raise HTTPException(404, "membre introuvable")


@app.get("/api/v1/family/members/{member_id}/sharing")
def get_sharing(member_id: UUID, fam: Fam):
    _member(fam, member_id)
    with pool.connection() as c:
        restricted = c.execute("SELECT restricted FROM users WHERE id = %s", (member_id,)).fetchone()["restricted"]
        rules = c.execute("SELECT date_from, date_to, people, albums FROM share_rules WHERE viewer_id = %s "
                          "ORDER BY created_at", (member_id,)).fetchall()
    return {"restricted": restricted, "rules": rules}


@app.put("/api/v1/family/members/{member_id}/sharing")
def put_sharing(member_id: UUID, body: Sharing, fam: Fam):
    """Remplace l'accès d'un membre : tout voir, ou seulement ses médias + ceux qui suivent une des règles."""
    _member(fam, member_id)
    with pool.connection() as c, c.transaction():
        c.execute("UPDATE users SET restricted = %s WHERE id = %s", (body.restricted, member_id))
        c.execute("DELETE FROM share_rules WHERE viewer_id = %s", (member_id,))
        for r in body.rules:
            c.execute("INSERT INTO share_rules (viewer_id, date_from, date_to, people, albums) VALUES (%s, %s, %s, %s, %s)",
                      (member_id, r.date_from, r.date_to, r.people, r.albums))
    return get_sharing(member_id, fam)


class Login(BaseModel):
    email: str = Field(max_length=254)
    password: str = Field(max_length=256)


_redis = redis.Redis.from_url(REDIS_URL)


def _too_many(key: str, limit: int, window_s: int) -> bool:
    """Compteur glissant simple dans Redis (partagé entre workers uvicorn)."""
    n = _redis.incr(key)
    if n == 1:
        _redis.expire(key, window_s)
    return n > limit


@app.post("/api/v1/auth/login")
def login(body: Login, request: Request):
    # Serveur accessible depuis Internet (Tailscale Funnel) : freiner les essais de mots de passe en rafale.
    # Par compte (10 / 15 min) et par adresse IP (30 / 15 min).
    email_key = f"login:email:{body.email.strip().lower()}"
    ip = (request.headers.get("x-forwarded-for") or (request.client.host if request.client else "?")).split(",")[0].strip()
    if _too_many(email_key, 10, 900) | _too_many(f"login:ip:{ip}", 30, 900):
        raise HTTPException(429, "trop de tentatives, réessaie dans 15 minutes")
    with pool.connection() as c:
        u = c.execute("SELECT id, password_hash FROM users WHERE lower(email) = lower(%s)",
                      (body.email,)).fetchone()
    if u is None or not verify_password(body.password, u["password_hash"]):
        raise HTTPException(401, "identifiants invalides")
    _redis.delete(email_key)
    exp = datetime.now(timezone.utc) + TOKEN_TTL
    token = jwt.encode({"sub": str(u["id"]), "exp": exp}, JWT_SECRET, algorithm="HS256")
    return {"access_token": token, "token_type": "bearer", "expires_at": exp}


# ---------- Synchronisation ----------


class CheckHashes(BaseModel):
    hashes: list[Sha256] = Field(max_length=1000)


@app.post("/api/v1/sync/check-hashes")
def check_hashes(body: CheckHashes, fam: Fam):
    with pool.connection() as c:
        rows = c.execute("SELECT encode(sha256, 'hex') AS h, id FROM media "
                         "WHERE owner_id = ANY(%s) AND sha256 = ANY(%s)",
                         (fam, [bytes.fromhex(h) for h in body.hashes])).fetchall()
    # Un média en corbeille compte comme présent : le téléphone ne doit pas le renvoyer.
    return {"existing": {r["h"]: str(r["id"]) for r in rows}}


class UploadInit(BaseModel):
    sha256: Sha256
    size: int = Field(gt=0, le=MAX_UPLOAD)
    filename: str = Field(min_length=1, max_length=255)
    mime_type: str = Field(pattern=r"^(image|video)/[\w.+-]+$")
    taken_at: datetime | None = None  # MediaStore DATE_TAKEN, repli si le fichier n'a pas d'EXIF


@app.post("/api/v1/uploads")
def init_upload(body: UploadInit, uid: User, fam: Fam):
    sha = bytes.fromhex(body.sha256)
    with pool.connection() as c:
        # déjà dans la bibliothèque familiale (envoyé par un autre membre) : rien à transférer
        m = c.execute("SELECT id FROM media WHERE owner_id = ANY(%s) AND sha256 = %s", (fam, sha)).fetchone()
        if m:
            return {"status": "exists", "media_id": str(m["id"])}
        # Reprise : même fichier déjà en cours => on renvoie la session et son offset
        s = c.execute("SELECT id, received_bytes FROM upload_sessions "
                      "WHERE owner_id = %s AND sha256 = %s AND expires_at > now()", (uid, sha)).fetchone()
        if s is None:
            s = c.execute(
                "INSERT INTO upload_sessions (owner_id, sha256, size_bytes, original_name, mime_type, "
                "client_taken_at) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id, received_bytes",
                (uid, sha, body.size, body.filename, body.mime_type, body.taken_at)).fetchone()
            part_path(s["id"]).touch()
    return {"status": "upload", "upload_id": str(s["id"]), "offset": s["received_bytes"],
            "chunk_max": CHUNK_MAX}


@app.get("/api/v1/uploads/{upload_id}")
def upload_status(upload_id: UUID, uid: User):
    with pool.connection() as c:
        s = c.execute("SELECT received_bytes, size_bytes FROM upload_sessions WHERE id = %s AND owner_id = %s",
                      (upload_id, uid)).fetchone()
    if s is None:
        raise HTTPException(404, "session inconnue ou expirée")
    return {"offset": s["received_bytes"], "size": s["size_bytes"]}


@app.put("/api/v1/uploads/{upload_id}")
async def upload_chunk(upload_id: UUID, request: Request, uid: User,
                       content_range: Annotated[str, Header()]):
    try:
        start, end, total = parse_content_range(content_range, CHUNK_MAX)
    except ValueError as e:
        raise HTTPException(400, str(e))
    expected = end - start + 1
    # shortcut: la connexion reste tenue pendant le transfert du bloc (verrou de session) ;
    # avec >10 uploads simultanés, agrandir le pool ou passer à un verrou fichier.
    with pool.connection() as c, c.transaction():
        try:
            s = c.execute("SELECT received_bytes, size_bytes FROM upload_sessions "
                          "WHERE id = %s AND owner_id = %s FOR UPDATE NOWAIT", (upload_id, uid)).fetchone()
        except psycopg.errors.LockNotAvailable:
            raise HTTPException(409, "un autre bloc est en cours d'envoi pour cette session")
        if s is None:
            raise HTTPException(404, "session inconnue ou expirée")
        if total != s["size_bytes"]:
            raise HTTPException(400, "taille totale différente de l'initialisation")
        if start != s["received_bytes"]:
            raise HTTPException(409, {"message": "offset inattendu", "offset": s["received_bytes"]})
        written = 0
        with open(part_path(upload_id), "r+b") as f:
            f.seek(start)  # écrase un éventuel bloc précédent interrompu
            async for chunk in request.stream():
                written += len(chunk)
                if written > expected:
                    raise HTTPException(400, "corps plus long que Content-Range")
                await run_in_threadpool(f.write, chunk)
            f.truncate()
        if written != expected:
            raise HTTPException(400, {"message": "bloc incomplet", "offset": start})
        c.execute("UPDATE upload_sessions SET received_bytes = %s, expires_at = now() + interval '24 hours' "
                  "WHERE id = %s", (end + 1, upload_id))
    return {"offset": end + 1}


@app.post("/api/v1/uploads/{upload_id}/complete", status_code=202)
def complete_upload(upload_id: UUID, uid: User, fam: Fam, response: Response, priority: bool = False):
    part = part_path(upload_id)
    with pool.connection() as c, c.transaction():
        s = c.execute("SELECT * FROM upload_sessions WHERE id = %s AND owner_id = %s FOR UPDATE",
                      (upload_id, uid)).fetchone()
        if s is None:
            raise HTTPException(404, "session inconnue ou expirée")
        if s["received_bytes"] != s["size_bytes"]:
            raise HTTPException(409, {"message": "upload incomplet", "offset": s["received_bytes"]})
        c.execute("DELETE FROM upload_sessions WHERE id = %s", (upload_id,))
        if not part.exists():
            response.status_code = 410
            return {"status": "restart", "message": "fichier temporaire perdu, recommencer l'upload"}
        dup = c.execute("SELECT id FROM media WHERE owner_id = ANY(%s) AND sha256 = %s", (fam, s["sha256"])).fetchone()
        if dup:  # envoyé entre-temps par un autre membre de la famille
            part.unlink()
            response.status_code = 200
            return {"status": "exists", "media_id": str(dup["id"])}
        row = c.execute(
            "INSERT INTO media (owner_id, sha256, kind, mime_type, original_name, size_bytes, taken_at) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s) ON CONFLICT (owner_id, sha256) DO NOTHING RETURNING id",
            (uid, s["sha256"], "video" if s["mime_type"].startswith("video/") else "image",
             s["mime_type"], s["original_name"], s["size_bytes"], s["client_taken_at"])).fetchone()
        if row is None:  # même fichier terminé entre-temps depuis un autre appareil
            part.unlink()
            existing = c.execute("SELECT id FROM media WHERE owner_id = %s AND sha256 = %s",
                                 (uid, s["sha256"])).fetchone()
            response.status_code = 200
            return {"status": "exists", "media_id": str(existing["id"])}
        media_id = str(row["id"])
        # Le SHA-256 réel est vérifié par le worker avant l'entrée en bibliothèque (tasks._ingest).
        part.rename(QUARANTINE_DIR / media_id)
    # priority : ajout manuel (version web, « + Ajouter ») -> file interactive, pas derrière la sauvegarde auto.
    # Si Redis est indisponible, tasks.housekeeping relance l'ingest plus tard.
    ingest.apply_async(args=[media_id], queue="query" if priority else "media")
    return {"status": "processing", "media_id": media_id}


# ---------- Consultation ----------

MEDIA_COLS = "m.id, encode(m.sha256, 'hex') AS sha256, m.kind, m.mime_type, m.taken_at, " \
             "m.width, m.height, m.duration_ms, m.owner_id"


@app.get("/api/v1/media/timeline")
def timeline(fam: Fam, cursor: str | None = None, limit: int = Query(200, ge=1, le=500)):
    params = {"fam": fam, "limit": limit}
    after = ""
    if cursor:
        try:
            params["ts"], params["cid"] = decode_cursor(cursor)
        except ValueError:
            raise HTTPException(400, "curseur invalide")
        after = "AND (m.taken_at, m.id) < (%(ts)s, %(cid)s)"
    with pool.connection() as c:
        rows = c.execute(
            f"SELECT {MEDIA_COLS} FROM media m WHERE {ready(fam)} "
            f"{after} ORDER BY m.taken_at DESC, m.id DESC LIMIT %(limit)s",
            params).fetchall()
    last = rows[-1] if len(rows) == limit else None
    return {"items": rows, "next_cursor": encode_cursor(last["taken_at"], last["id"]) if last else None}


@app.get("/api/v1/search/semantic")
def search_semantic(fam: Fam, q: str = Query(min_length=2, max_length=200),
                    limit: int = Query(60, ge=1, le=200)):
    try:  # CLIP ne vit que dans le worker (RAM) ; le 1er appel attend le chargement du modèle
        vec = np.array(embed_query.apply_async(args=[q]).get(timeout=120), dtype=np.float32)
    except celery.exceptions.TimeoutError:
        raise HTTPException(503, "moteur de recherche indisponible, réessayer")
    with pool.connection() as c, c.transaction():
        c.execute("SET LOCAL hnsw.iterative_scan = strict_order")  # le filtre owner ne vide pas le top-k
        rows = c.execute(
            f"SELECT {MEDIA_COLS}, 1 - (e.embedding <=> %(v)s) AS score "
            "FROM embeddings e JOIN media m ON m.id = e.media_id "
            f"WHERE {ready(fam)} AND e.model = %(model)s "
            "ORDER BY e.embedding <=> %(v)s LIMIT %(limit)s",
            {"v": vec, "fam": fam, "model": CLIP_MODEL_ID, "limit": limit}).fetchall()
    return {"items": rows}


class Ids(BaseModel):
    ids: list[UUID] = Field(min_length=1, max_length=500)


@app.get("/api/v1/categories")
def list_categories(fam: Fam):
    """Catégories calculées localement par CLIP (Plage, Nourriture, Animaux…), avec un nombre et une vignette."""
    with pool.connection() as c:
        rows = c.execute(
            "SELECT t.name, count(*) AS media_count, (array_agg(m.id ORDER BY m.taken_at DESC))[1] AS cover_id "
            "FROM media_tags mt JOIN tags t ON t.id = mt.tag_id JOIN media m ON m.id = mt.media_id "
            f"WHERE mt.source = 'clip' AND {ready(fam)} GROUP BY t.name ORDER BY count(*) DESC", {"fam": fam}).fetchall()
    return {"categories": rows}


@app.get("/api/v1/media/query")
def media_query(fam: Fam, person_id: UUID | None = None, album_id: UUID | None = None,
                city: str | None = None, country_code: str | None = None, tag: str | None = None,
                limit: int = Query(500, ge=1, le=2000)):
    """Contenu d'une personne, d'un album ou d'un lieu."""
    params: dict = {"fam": fam, "limit": limit}
    conds = []
    if person_id:
        conds.append("EXISTS (SELECT 1 FROM faces f WHERE f.media_id = m.id AND f.person_id = %(person)s)")
        params["person"] = person_id
    if album_id:
        conds.append("EXISTS (SELECT 1 FROM album_media am JOIN albums a ON a.id = am.album_id "
                     "WHERE am.media_id = m.id AND a.id = %(album)s AND a.owner_id = ANY(%(fam)s))")
        params["album"] = album_id
    if city:
        conds.append("m.city = %(city)s AND m.country_code IS NOT DISTINCT FROM %(cc)s")
        params |= {"city": city, "cc": country_code}
    if tag:
        conds.append("EXISTS (SELECT 1 FROM media_tags mt JOIN tags t ON t.id = mt.tag_id "
                     "WHERE mt.media_id = m.id AND t.name = %(tag)s)")
        params["tag"] = tag
    if not conds:
        raise HTTPException(400, "filtre requis : person_id, album_id, city ou tag")
    # shortcut: pas de curseur (2000 max) ; paginer comme la timeline si une collection dépasse ça.
    with pool.connection() as c:
        rows = c.execute(f"SELECT {MEDIA_COLS} FROM media m WHERE {ready(fam)} AND {' AND '.join(conds)} "
                         "ORDER BY m.taken_at DESC LIMIT %(limit)s", params).fetchall()
    return {"items": rows}


@app.get("/api/v1/media/recent")
def media_recent(fam: Fam, limit: int = Query(300, ge=1, le=1000)):
    """Derniers ajouts à la bibliothèque (date d'import, pas de prise de vue)."""
    with pool.connection() as c:
        rows = c.execute(f"SELECT {MEDIA_COLS}, m.created_at FROM media m WHERE {ready(fam)} "
                         "ORDER BY m.created_at DESC LIMIT %(limit)s", {"fam": fam, "limit": limit}).fetchall()
    return {"items": rows}


class EnhanceReq(BaseModel):
    mode: Literal["auto", "gemini"] = "auto"


@app.post("/api/v1/media/{media_id}/enhance", status_code=202)
def enhance_media(media_id: UUID, body: EnhanceReq, uid: User, fam: Fam):
    """Retouche : « auto » sur le serveur (privé, gratuit) ou « gemini » (photo envoyée à Google)."""
    row = _owned(media_id, fam)
    if not row["mime_type"].startswith("image/"):
        raise HTTPException(400, "seules les photos peuvent être retouchées")
    if body.mode == "gemini" and not os.environ.get("GEMINI_API_KEY"):
        raise HTTPException(400, "clé Gemini non configurée sur le serveur (GEMINI_API_KEY)")
    enhance_task.delay(str(media_id), str(uid), body.mode)
    return {"status": "processing"}


# ---------- Nettoyage : photos ratées, doublons, suppression par mots ----------
# Seuils calibrés sur la distribution réelle (tasks.category_stats / quality) ; ajustables par variables d'env.
BLUR_MAX = float(os.environ.get("CLEANUP_BLUR_MAX", "40"))  # ~3 % des photos sur une bibliothèque réelle
DARK_MAX = float(os.environ.get("CLEANUP_DARK_MAX", "28"))
BRIGHT_MIN = float(os.environ.get("CLEANUP_BRIGHT_MIN", "242"))
DUP_MAX_DIST = float(os.environ.get("CLEANUP_DUP_MAX_DIST", "0.04"))  # distance cosinus CLIP
WORDS_MIN_SCORE = float(os.environ.get("CLEANUP_WORDS_MIN_SCORE", "0.2"))
IMG = "m.kind = 'image'"


def _duplicate_groups(c, fam) -> list[dict]:
    """Rafales : photos presque identiques prises à moins de 3 min d'écart. La plus nette est gardée."""
    pairs = c.execute(
        "SELECT a.id AS a, b.id AS b FROM media a "
        "JOIN embeddings ea ON ea.media_id = a.id AND ea.model = %(model)s "
        "CROSS JOIN LATERAL (SELECT m2.id FROM media m2 "
        "  JOIN embeddings e2 ON e2.media_id = m2.id AND e2.model = %(model)s "
        "  WHERE m2.owner_id = ANY(%(fam)s) AND m2.status = 'ready' AND m2.deleted_at IS NULL AND m2.kind = 'image' "
        "  AND m2.id > a.id AND m2.taken_at BETWEEN a.taken_at - interval '3 minutes' AND a.taken_at + interval '3 minutes' "
        # une retouche (copie « améliorée ») n'est pas un doublon de son original
        "  AND m2.edited_from IS DISTINCT FROM a.id AND a.edited_from IS DISTINCT FROM m2.id "
        "  AND (e2.embedding <=> ea.embedding) < %(dist)s) b "
        "WHERE a.owner_id = ANY(%(fam)s) AND a.status = 'ready' AND a.deleted_at IS NULL AND a.kind = 'image'",
        {"fam": fam, "model": CLIP_MODEL_ID, "dist": DUP_MAX_DIST}).fetchall()
    parent: dict = {}

    def find(x):
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for p in pairs:
        parent[find(p["a"])] = find(p["b"])
    groups: dict = {}
    for x in parent:
        groups.setdefault(find(x), []).append(x)
    ids = [i for g in groups.values() for i in g]
    if not ids:
        return []
    rows = {r["id"]: r for r in c.execute(
        f"SELECT {MEDIA_COLS}, m.sharpness FROM media m WHERE m.id = ANY(%s)", (ids,)).fetchall()}
    out = []
    for n, members in enumerate(sorted(groups.values(), key=lambda g: rows[g[0]]["taken_at"], reverse=True)):
        keep = max(members, key=lambda i: (rows[i]["sharpness"] or 0, rows[i]["width"] or 0))
        for i in sorted(members, key=lambda i: rows[i]["taken_at"]):
            out.append({**rows[i], "group": n, "suggested": i != keep})
    return out


@app.get("/api/v1/cleanup/{kind}")
def cleanup(kind: Literal["duplicates", "blurry", "dark", "screenshots", "words"], fam: Fam,
            q: str | None = Query(None, max_length=200)):
    """Suggestions de suppression ; `suggested` = présélectionné dans l'app (l'utilisateur valide toujours)."""
    p = {"fam": fam}
    with pool.connection() as c:
        if kind == "duplicates":
            return {"items": _duplicate_groups(c, fam)}
        if kind == "blurry":
            # Captures d'écran et documents ont de grands aplats : faible « netteté » sans être floues
            sql = (f"SELECT {MEDIA_COLS} FROM media m WHERE {ready(fam, shared=False)} AND {IMG} AND m.sharpness < %(t)s "
                   "AND NOT EXISTS (SELECT 1 FROM media_tags mt JOIN tags t ON t.id = mt.tag_id WHERE mt.media_id = m.id "
                   "AND mt.source = 'clip' AND t.name IN ('Captures d''écran', 'Documents')) ORDER BY m.sharpness")
            p["t"] = BLUR_MAX
        elif kind == "dark":
            sql = (f"SELECT {MEDIA_COLS} FROM media m WHERE {ready(fam, shared=False)} AND {IMG} "
                   "AND (m.brightness < %(lo)s OR m.brightness > %(hi)s) ORDER BY m.brightness")
            p |= {"lo": DARK_MAX, "hi": BRIGHT_MIN}
        elif kind == "screenshots":
            sql = (f"SELECT {MEDIA_COLS} FROM media m WHERE {ready(fam, shared=False)} AND EXISTS (SELECT 1 FROM media_tags mt "
                   "JOIN tags t ON t.id = mt.tag_id WHERE mt.media_id = m.id AND mt.source = 'clip' "
                   "AND t.name = 'Captures d''écran') ORDER BY m.taken_at DESC")
        else:
            if not q or len(q.strip()) < 2:
                raise HTTPException(400, "décris les photos à supprimer (paramètre q)")
            try:
                vec = np.array(embed_query.apply_async(args=[q.strip()]).get(timeout=120), dtype=np.float32)
            except celery.exceptions.TimeoutError:
                raise HTTPException(503, "moteur de recherche indisponible, réessayer")
            c.execute("SET hnsw.iterative_scan = strict_order")
            sql = (f"SELECT {MEDIA_COLS}, 1 - (e.embedding <=> %(v)s) AS score FROM embeddings e "
                   f"JOIN media m ON m.id = e.media_id WHERE {ready(fam, shared=False)} AND e.model = %(model)s "
                   "AND 1 - (e.embedding <=> %(v)s) >= %(min)s ORDER BY e.embedding <=> %(v)s")
            p |= {"v": vec, "model": CLIP_MODEL_ID, "min": WORDS_MIN_SCORE}
        rows = c.execute(sql + " LIMIT 500", p).fetchall()
    return {"items": [{**r, "suggested": True} for r in rows]}


@app.get("/api/v1/trash")
def list_trash(fam: Fam):
    """Corbeille du serveur : rien n'est effacé définitivement, tout reste restaurable."""
    with pool.connection() as c:
        rows = c.execute(f"SELECT {MEDIA_COLS}, m.deleted_at FROM media m WHERE m.owner_id = ANY(%s) "
                         "AND m.deleted_at IS NOT NULL ORDER BY m.deleted_at DESC LIMIT 2000", (fam,)).fetchall()
    return {"items": rows}


@app.post("/api/v1/media/restore")
def restore_media(body: Ids, fam: Fam):
    with pool.connection() as c:
        n = c.execute("UPDATE media SET deleted_at = NULL WHERE owner_id = ANY(%s) AND id = ANY(%s) "
                      "AND deleted_at IS NOT NULL", (fam, body.ids)).rowcount
    return {"restored": n}


@app.post("/api/v1/media/trash")
def trash_media(body: Ids, fam: Fam):
    """Corbeille : le média disparaît des listes ; check-hashes le voit encore, le téléphone ne le renvoie pas."""
    with pool.connection() as c:
        n = c.execute("UPDATE media SET deleted_at = now() WHERE owner_id = ANY(%s) AND id = ANY(%s) "
                      "AND deleted_at IS NULL", (fam, body.ids)).rowcount
    return {"trashed": n}


@app.get("/api/v1/media/{media_id}")
def media_details(media_id: UUID, fam: Fam):
    with pool.connection() as c:
        row = c.execute(
            f"SELECT {MEDIA_COLS}, m.original_name, m.size_bytes, m.camera_make, m.camera_model, "
            "m.latitude, m.longitude, m.city, m.admin1, m.country_code, m.description, m.ocr_text, "
            "(SELECT coalesce(u.display_name, u.email) FROM users u WHERE u.id = m.owner_id) AS uploaded_by, "
            "coalesce((SELECT array_agg(t.name ORDER BY t.name) FROM media_tags mt "
            "          JOIN tags t ON t.id = mt.tag_id WHERE mt.media_id = m.id), '{}') AS tags, "
            "coalesce((SELECT json_agg(jsonb_build_object('id', p.id, 'name', p.name, 'face_id', f.id) ORDER BY f.bbox[1]) "
            "          FROM faces f JOIN people p ON p.id = f.person_id WHERE f.media_id = m.id AND NOT p.hidden), '[]') AS people "
            "FROM media m WHERE m.id = %(id)s AND " + ready(fam), {"id": media_id, "fam": fam}).fetchone()
    if row is None:
        raise HTTPException(404, "média introuvable")
    return row


def _social(c, media_id: UUID, uid: UUID, fam: list[UUID]) -> dict:
    if not c.execute("SELECT 1 FROM media m WHERE m.id = %(id)s AND " + ready(fam), {"id": media_id, "fam": fam}).fetchone():
        raise HTTPException(404, "média introuvable")
    likes = c.execute(
        "SELECT l.user_id, coalesce(u.display_name, u.email) AS name FROM media_likes l JOIN users u ON u.id = l.user_id "
        "WHERE l.media_id = %s ORDER BY l.created_at", (media_id,)).fetchall()
    comments = c.execute(
        "SELECT mc.id, mc.body, mc.created_at, coalesce(u.display_name, u.email) AS author, mc.user_id = %s AS mine "
        "FROM media_comments mc JOIN users u ON u.id = mc.user_id WHERE mc.media_id = %s ORDER BY mc.created_at",
        (uid, media_id)).fetchall()
    return {"liked": any(l["user_id"] == uid for l in likes), "likes": [l["name"] for l in likes], "comments": comments}


@app.get("/api/v1/media/{media_id}/social")
def media_social(media_id: UUID, uid: User, fam: Fam):
    with pool.connection() as c:
        return _social(c, media_id, uid, fam)


class LikeBody(BaseModel):
    liked: bool


@app.put("/api/v1/media/{media_id}/like")
def set_like(media_id: UUID, body: LikeBody, uid: User, fam: Fam):
    with pool.connection() as c, c.transaction():
        _social(c, media_id, uid, fam)  # contrôle d'accès
        if body.liked:
            c.execute("INSERT INTO media_likes (media_id, user_id) VALUES (%s, %s) ON CONFLICT DO NOTHING", (media_id, uid))
        else:
            c.execute("DELETE FROM media_likes WHERE media_id = %s AND user_id = %s", (media_id, uid))
        return _social(c, media_id, uid, fam)


class CommentBody(BaseModel):
    body: str = Field(min_length=1, max_length=2000)


@app.post("/api/v1/media/{media_id}/comments")
def add_comment(media_id: UUID, body: CommentBody, uid: User, fam: Fam):
    text = body.body.strip()
    if not text:
        raise HTTPException(400, "commentaire vide")
    with pool.connection() as c, c.transaction():
        _social(c, media_id, uid, fam)
        c.execute("INSERT INTO media_comments (media_id, user_id, body) VALUES (%s, %s, %s)", (media_id, uid, text))
        return _social(c, media_id, uid, fam)


@app.delete("/api/v1/comments/{comment_id}")
def delete_comment(comment_id: UUID, uid: User, fam: Fam):
    """Seul l'auteur supprime son commentaire."""
    with pool.connection() as c, c.transaction():
        row = c.execute("DELETE FROM media_comments WHERE id = %s AND user_id = %s RETURNING media_id",
                        (comment_id, uid)).fetchone()
        if row is None:
            raise HTTPException(404, "commentaire introuvable")
        return _social(c, row["media_id"], uid, fam)


@app.get("/api/v1/people")
def list_people(fam: Fam):
    with pool.connection() as c:
        rows = c.execute(
            "SELECT p.id, p.name, count(DISTINCT f.media_id) AS media_count, "
            "       (array_agg(f.id ORDER BY f.det_score DESC))[1] AS cover_face_id "
            "FROM people p JOIN faces f ON f.person_id = p.id "
            "JOIN media m ON m.id = f.media_id AND m.deleted_at IS NULL "
            "WHERE p.owner_id = ANY(%s) AND NOT p.hidden GROUP BY p.id "
            "HAVING count(DISTINCT f.media_id) >= 2 "  # un visage croisé une seule fois n'est pas une « personne »
            "ORDER BY (p.name IS NULL), count(DISTINCT f.media_id) DESC LIMIT 100", (fam,)).fetchall()
    return {"people": rows}


class PersonPatch(BaseModel):
    name: str | None = Field(None, max_length=100)
    hidden: bool | None = None


@app.patch("/api/v1/people/{person_id}")
def patch_person(person_id: UUID, body: PersonPatch, fam: Fam):
    fields = body.model_dump(exclude_unset=True)
    if not fields:
        raise HTTPException(400, "rien à modifier")
    if "name" in fields:
        fields["name"] = (fields["name"] or "").strip() or None
    with pool.connection() as c, c.transaction():
        if not c.execute("SELECT 1 FROM people WHERE id = %s AND owner_id = ANY(%s)", (person_id, fam)).fetchone():
            raise HTTPException(404, "personne introuvable")
        if fields.get("name"):
            # Même nom qu'une personne existante = le clustering l'avait coupée en deux : on fusionne
            twin = c.execute("SELECT id FROM people WHERE owner_id = ANY(%s) AND id <> %s AND lower(name) = lower(%s)",
                             (fam, person_id, fields["name"])).fetchone()
            if twin:
                c.execute("UPDATE faces SET person_id = %s WHERE person_id = %s", (twin["id"], person_id))
                c.execute("DELETE FROM people WHERE id = %s", (person_id,))
                return {"id": str(twin["id"]), "merged": True}
        sets = ", ".join(f"{k} = %({k})s" for k in fields)  # clés du modèle pydantic, pas de l'entrée brute
        c.execute(f"UPDATE people SET {sets} WHERE id = %(id)s", {**fields, "id": person_id})
    return {"id": str(person_id), "merged": False}


class FaceAssign(BaseModel):
    person_id: UUID | None = None  # personne existante
    name: str | None = Field(None, min_length=1, max_length=100)  # ou nom : personne existante de ce nom, sinon nouvelle
    # ni l'un ni l'autre : on retire le visage (poster, statue, inconnu…)


@app.put("/api/v1/faces/{face_id}/person")
def assign_face(face_id: UUID, body: FaceAssign, fam: Fam):
    """Correction manuelle : le visage n'est plus jamais réattribué automatiquement (faces.manual)."""
    with pool.connection() as c, c.transaction():
        face = c.execute("SELECT owner_id FROM faces WHERE id = %s AND owner_id = ANY(%s)", (face_id, fam)).fetchone()
        if face is None:
            raise HTTPException(404, "visage introuvable")
        person_id = None
        if body.person_id:
            if not c.execute("SELECT 1 FROM people WHERE id = %s AND owner_id = ANY(%s)", (body.person_id, fam)).fetchone():
                raise HTTPException(404, "personne introuvable")
            person_id = body.person_id
        elif body.name and body.name.strip():
            name = body.name.strip()
            row = c.execute("SELECT id FROM people WHERE owner_id = ANY(%s) AND lower(name) = lower(%s) LIMIT 1",
                            (fam, name)).fetchone() or c.execute(
                "INSERT INTO people (owner_id, name) VALUES (%s, %s) RETURNING id", (face["owner_id"], name)).fetchone()
            person_id = row["id"]
        c.execute("UPDATE faces SET person_id = %s, manual = true WHERE id = %s", (person_id, face_id))
    return {"person_id": str(person_id) if person_id else None}


@app.get("/api/v1/faces/{face_id}/thumb")
def face_thumb(face_id: UUID, fam: Fam):
    import pyvips

    with pool.connection() as c:
        f = c.execute("SELECT media_id, bbox FROM faces WHERE id = %s AND owner_id = ANY(%s)", (face_id, fam)).fetchone()
    if f is None:
        raise HTTPException(404, "visage introuvable")
    img = pyvips.Image.new_from_file(str(thumb_path(f["media_id"], "md")))
    x1, y1, x2, y2 = f["bbox"]
    # carré centré sur le visage, avec 40 % de marge, borné à l'image
    side = min(max((x2 - x1) * img.width, (y2 - y1) * img.height) * 1.4, img.width, img.height)
    left = min(max((x1 + x2) / 2 * img.width - side / 2, 0), img.width - side)
    top = min(max((y1 + y2) / 2 * img.height - side / 2, 0), img.height - side)
    crop = img.crop(int(left), int(top), int(side), int(side)).thumbnail_image(160)
    return Response(crop.webpsave_buffer(Q=80), media_type="image/webp",
                    headers={"Cache-Control": "private, max-age=31536000, immutable"})


@app.get("/api/v1/places")
def list_places(fam: Fam):
    with pool.connection() as c:
        rows = c.execute(
            "SELECT m.city, m.country_code, count(*) AS media_count, "
            "       (array_agg(m.id ORDER BY m.taken_at DESC))[1] AS cover_id "
            f"FROM media m WHERE {ready(fam)} AND m.city IS NOT NULL "
            "GROUP BY m.city, m.country_code ORDER BY count(*) DESC LIMIT 60", {"fam": fam}).fetchall()
    return {"places": rows}


@app.get("/api/v1/albums")
def list_albums(fam: Fam):
    with pool.connection() as c:
        rows = c.execute(
            "SELECT a.id, a.title, count(m.id) AS media_count, "
            "       coalesce(a.cover_media_id, (array_agg(m.id ORDER BY am.added_at DESC))[1]) AS cover_id "
            "FROM albums a LEFT JOIN album_media am ON am.album_id = a.id "
            "LEFT JOIN media m ON m.id = am.media_id AND m.deleted_at IS NULL "
            "WHERE a.owner_id = ANY(%s) GROUP BY a.id ORDER BY a.created_at DESC", (fam,)).fetchall()
    return {"albums": rows}


class AlbumCreate(BaseModel):
    title: str = Field(min_length=1, max_length=100)
    media_ids: list[UUID] = Field(default_factory=list, max_length=500)


def _add_to_album(c, album_id, ids, fam) -> int:
    return c.execute("INSERT INTO album_media (album_id, media_id) SELECT %s, id FROM media "
                     "WHERE owner_id = ANY(%s) AND id = ANY(%s) ON CONFLICT DO NOTHING", (album_id, fam, ids)).rowcount


@app.post("/api/v1/albums", status_code=201)
def create_album(body: AlbumCreate, uid: User, fam: Fam):
    with pool.connection() as c, c.transaction():
        album_id = c.execute("INSERT INTO albums (owner_id, title) VALUES (%s, %s) RETURNING id",
                             (uid, body.title.strip())).fetchone()["id"]
        added = _add_to_album(c, album_id, body.media_ids, fam)
    return {"id": str(album_id), "added": added}


@app.post("/api/v1/albums/{album_id}/media")
def add_to_album(album_id: UUID, body: Ids, fam: Fam):
    with pool.connection() as c, c.transaction():
        if not c.execute("SELECT 1 FROM albums WHERE id = %s AND owner_id = ANY(%s)", (album_id, fam)).fetchone():
            raise HTTPException(404, "album introuvable")
        return {"added": _add_to_album(c, album_id, body.ids, fam)}


@app.delete("/api/v1/albums/{album_id}", status_code=204)
def delete_album(album_id: UUID, fam: Fam):
    """Supprime l'album, pas les photos."""
    with pool.connection() as c:
        if not c.execute("DELETE FROM albums WHERE id = %s AND owner_id = ANY(%s) RETURNING id",
                         (album_id, fam)).fetchone():
            raise HTTPException(404, "album introuvable")
    return Response(status_code=204)


MV_COLS = ("v.id, to_char(v.month, 'YYYY-MM') AS month, v.status, v.cover_media_id, v.duration_s, "
           "coalesce(cardinality(v.media_ids), 0) AS media_count")


@app.get("/api/v1/memory-videos")
def list_memory_videos(fam: Fam):
    with pool.connection() as c:
        rows = c.execute(f"SELECT {MV_COLS} FROM memory_videos v WHERE v.owner_id = ANY(%s) "
                         "ORDER BY v.month DESC LIMIT 36", (fam,)).fetchall()
    return {"videos": rows}


class MemoryVideoReq(BaseModel):
    month: str = Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")


@app.post("/api/v1/memory-videos", status_code=202)
def create_memory_video(body: MemoryVideoReq, uid: User):
    """Crée ou refait la vidéo d'un mois (montage en arrière-plan, 10-20 min sur la VM)."""
    month = datetime.strptime(body.month + "-01", "%Y-%m-%d").date()
    with pool.connection() as c:
        return queue_memory_video(c, uid, month)


@app.get("/api/v1/memory-videos/{video_id}/video")
def memory_video_file(video_id: UUID, fam: Fam):
    with pool.connection() as c:
        ok = c.execute("SELECT 1 FROM memory_videos WHERE id = %s AND owner_id = ANY(%s) AND status = 'ready'",
                       (video_id, fam)).fetchone()
    path = MEMORY_VIDEO_DIR / f"{video_id}.mp4"
    if not ok or not path.exists():
        raise HTTPException(404, "vidéo introuvable")
    return FileResponse(path, media_type="video/mp4")


@app.get("/api/v1/memories")
def memories(fam: Fam):
    """« Il y a N ans » : la même semaine (± 3 jours) les années précédentes."""
    today = datetime.now()  # TZ du conteneur = fuseau de l'utilisateur ; taken_at est en heure locale
    with pool.connection() as c:
        # shortcut: fenêtre en jour de l'année, sans gérer le passage du 31 décembre au 1er janvier
        rows = c.execute(
            f"SELECT {MEDIA_COLS} FROM media m WHERE {ready(fam)} "
            "AND abs(extract(doy FROM m.taken_at) - %(doy)s) <= 3 "
            "AND extract(year FROM m.taken_at) < %(y)s ORDER BY m.taken_at DESC LIMIT 500",
            {"fam": fam, "doy": today.timetuple().tm_yday, "y": today.year}).fetchall()
    groups: dict[int, list] = {}
    for r in rows:
        groups.setdefault(today.year - r["taken_at"].year, []).append(r)
    return {"memories": [{"years_ago": y, "items": items} for y, items in groups.items()]}


# ---------- Import depuis un dossier du serveur (NAS) ----------
# shortcut: tout compte connecté peut parcourir IMPORT_ROOT (lecture seule) ; restreindre par utilisateur si le
# serveur est partagé entre plusieurs foyers.


def _import_dir(path: str) -> Path:
    try:
        p = safe_join(IMPORT_ROOT, path)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not p.is_dir():
        raise HTTPException(404, "dossier introuvable")
    return p


@app.get("/api/v1/import/browse")
def import_browse(uid: User, fam: Fam, path: str = Query("", max_length=1000)):
    require_unrestricted(fam)
    p = _import_dir(path)
    try:
        entries = list(p.iterdir())
    except OSError as e:
        raise HTTPException(503, f"dossier illisible (NAS indisponible ?) : {e}")
    return {
        "path": str(p.relative_to(IMPORT_ROOT.resolve())).replace("\\", "/").removeprefix("."),
        "dirs": sorted((e.name for e in entries if e.is_dir() and visible_dir(e.name, IMPORT_EXCLUDE)), key=str.lower),
        "media_files": sum(1 for e in entries if e.is_file() and media_mime(e.name)),
    }


class ImportReq(BaseModel):
    path: str = Field(max_length=1000)


@app.post("/api/v1/import/jobs", status_code=202)
def start_import(body: ImportReq, uid: User, fam: Fam):
    require_unrestricted(fam)
    p = _import_dir(body.path)
    rel = str(p.relative_to(IMPORT_ROOT.resolve())).replace("\\", "/").removeprefix(".")
    with pool.connection() as c:
        job_id = c.execute("INSERT INTO import_jobs (owner_id, path) VALUES (%s, %s) RETURNING id", (uid, rel)).fetchone()["id"]
    import_folder.delay(str(job_id))
    return {"id": str(job_id)}


@app.get("/api/v1/import/jobs")
def list_imports(uid: User):
    with pool.connection() as c:
        rows = c.execute("SELECT id, path, status, found, imported, skipped, failed, error, created_at, finished_at "
                         "FROM import_jobs WHERE owner_id = %s ORDER BY created_at DESC LIMIT 20", (uid,)).fetchall()
    return {"jobs": rows}


def _owned(media_id: UUID, fam: Family, shared: bool = False) -> dict:
    with pool.connection() as c:
        row = c.execute("SELECT m.storage_path, m.mime_type FROM media m WHERE m.id = %(id)s AND " + ready(fam, shared),
                        {"id": media_id, "fam": fam}).fetchone()
    if row is None:
        raise HTTPException(404, "média introuvable")
    return row


@app.get("/api/v1/media/{media_id}/thumb/{size}")
def get_thumb(media_id: UUID, size: Literal["sm", "md"], fam: Fam):
    _owned(media_id, fam, shared=True)
    return FileResponse(thumb_path(media_id, size), media_type="image/webp",
                        headers={"Cache-Control": "private, max-age=31536000, immutable"})


@app.get("/api/v1/media/{media_id}/original")
def get_original(media_id: UUID, fam: Fam):
    row = _owned(media_id, fam, shared=True)
    # FileResponse gère l'en-tête Range : lecture vidéo avec seek dans ExoPlayer
    return FileResponse(PHOTOS_DIR / row["storage_path"], media_type=row["mime_type"],
                        headers={"Cache-Control": "private, max-age=31536000, immutable"})


# Version navigateur (server/web) : montée en dernier pour ne masquer aucune route /api.
# Type MIME de l'APK Android déposé à côté (web/MemoriesBK.apk), sinon servi en text/plain.
mimetypes.add_type("application/vnd.android.package-archive", ".apk")
app.mount("/", StaticFiles(directory=Path(__file__).with_name("web"), html=True), name="web")


if __name__ == "__main__" and sys.argv[1:2] == ["create-user"] and len(sys.argv) == 3:
    import getpass

    pw = getpass.getpass("Mot de passe : ")
    if len(pw) < 10:
        sys.exit("10 caractères minimum")
    with pool.connection() as c:  # compte existant : réinitialise son mot de passe
        c.execute("INSERT INTO users (email, password_hash) VALUES (%s, %s) "
                  "ON CONFLICT ((lower(email))) DO UPDATE SET password_hash = EXCLUDED.password_hash",
                  (sys.argv[2], hash_password(pw)))
    print("compte créé ou mot de passe mis à jour")
