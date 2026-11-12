"""Tâches Celery du pipeline média. Lancement : voir docs/ARCHITECTURE.md §4."""
import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from functools import cache
from pathlib import Path

import psycopg
import redis
from celery import Celery
from celery.schedules import crontab
from celery.signals import worker_ready
from pgvector.psycopg.vector import register_vector_info
from psycopg.rows import dict_row
from psycopg.types import TypeInfo

import vlm
from metadata import date_from_name, extract_fields, media_mime, safe_join, storage_rel_path, thumb_rel_dir, visible_dir

PHOTOS_DIR = Path(os.environ.get("PHOTOS_DIR", "/photos"))
# Doit être sur le même système de fichiers que PHOTOS_DIR (rename atomique).
QUARANTINE_DIR = Path(os.environ.get("QUARANTINE_DIR", "/photos/.quarantine"))
THUMBS_DIR = Path(os.environ.get("THUMBS_DIR", "/thumbs"))
# Racine (lecture seule) que l'utilisateur peut parcourir et importer : partage NAS, dossiers copiés du PC…
IMPORT_ROOT = Path(os.environ.get("IMPORT_ROOT", "/import"))
IMPORT_EXCLUDE = set(filter(None, os.environ.get("IMPORT_EXCLUDE", "photos-app,trashbox").split(",")))
DSN = os.environ.get("DATABASE_URL", "postgresql://photos:photos@postgres/photos")

THUMB_SIZES = {"sm": 320, "md": 1440}
FACE_MAX_DIST = float(os.environ.get("FACE_MAX_DIST", "0.6"))  # distance cosinus ; 0.6 ≈ similarité 0.4
VLM_HOURLY_BUDGET = int(os.environ.get("VLM_HOURLY_BUDGET", "20"))  # ≈ quota quotidien gratuit / 24
VLM_RATE_LIMIT = os.environ.get("VLM_RATE_LIMIT", "8/m")  # sous le RPM gratuit, marge pour les retries

REDIS_URL = os.environ.get("REDIS_URL", "redis://redis:6379/0")
app = Celery("photos", broker=REDIS_URL, backend=REDIS_URL)
app.conf.update(
    task_acks_late=True,  # une tâche interrompue (OOM, redémarrage) est relivrée
    task_reject_on_worker_lost=True,
    worker_prefetch_multiplier=1,
    task_ignore_result=True,  # seul embed_query renvoie un résultat (à l'API)
    result_expires=300,
    # acks_late : une tâche non acquittée au-delà de ce délai est relivrée ; un import de NAS peut durer des heures
    broker_transport_options={"visibility_timeout": 24 * 3600},
    task_routes={
        "tasks.import_folder": {"queue": "media"},
        "tasks.embed_query": {"queue": "query"},
        "tasks.ingest": {"queue": "media"},
        "tasks.sweep_vlm": {"queue": "media"},
        "tasks.housekeeping": {"queue": "media"},
        "tasks.clip_embed": {"queue": "ml"},
        "tasks.categorize_all": {"queue": "ml"},
        "tasks.quality_all": {"queue": "ml"},
        "tasks.category_stats": {"queue": "query"},
        "tasks.enhance": {"queue": "query"},  # action demandée par l'utilisateur : pas derrière les imports
        "tasks.detect_faces": {"queue": "ml"},
        "tasks.describe": {"queue": "vlm"},
        # file peu utilisée : une vidéo (10-20 min) ne passe pas derrière des milliers d'imports
        "tasks.make_memory_video": {"queue": "vlm"},
        "tasks.monthly_memory_videos": {"queue": "vlm"},
    },
    beat_schedule={
        "sweep-vlm": {"task": "tasks.sweep_vlm", "schedule": 3600.0},
        "housekeeping": {"task": "tasks.housekeeping", "schedule": 900.0},
        "memory-videos": {"task": "tasks.monthly_memory_videos", "schedule": crontab(day_of_month="1", hour="3", minute="0")},
    },
)


_redis = redis.Redis.from_url(REDIS_URL)

try:  # cache d'opérations libvips : 100 Mo par défaut, inutile ici (chaque image n'est traitée qu'une fois)
    import pyvips

    pyvips.cache_set_max(0)
    pyvips.cache_set_max_mem(0)
except Exception:  # libvips absente (tests hors conteneur)
    pass


def once_per(key: str, seconds: int) -> bool:
    """Vrai au premier appel puis faux pendant `seconds` : évite de remettre en file une tâche déjà en attente."""
    return bool(_redis.set(key, 1, nx=True, ex=seconds))


class HashMismatch(Exception):
    pass


def part_path(upload_id) -> Path:
    return QUARANTINE_DIR / f"{upload_id}.part"


@cache
def _register_vector() -> None:
    # Une seule fois, en global : register_vector par connexion crée 8 classes jamais libérées (fuite du worker, 11/11)
    with psycopg.connect(DSN) as conn:
        register_vector_info(psycopg.adapters, TypeInfo.fetch(conn, "vector"))


def db() -> psycopg.Connection:
    _register_vector()
    return psycopg.connect(DSN, autocommit=True, row_factory=dict_row)


def family_ids(conn, user_id) -> list:
    """L'utilisateur et les membres de sa famille (bibliothèque commune)."""
    return [r["id"] for r in conn.execute(
        "SELECT id FROM users WHERE id = %(u)s OR family_id = (SELECT family_id FROM users WHERE id = %(u)s)",
        {"u": user_id}).fetchall()]


def store_categories(conn, media_id, names: list[str]) -> None:
    conn.execute("DELETE FROM media_tags WHERE media_id = %s AND source = 'clip'", (media_id,))
    for name in names:
        tag_id = conn.execute("INSERT INTO tags (name) VALUES (%s) ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name "
                              "RETURNING id", (name,)).fetchone()["id"]
        conn.execute("INSERT INTO media_tags (media_id, tag_id, source) VALUES (%s, %s, 'clip') ON CONFLICT DO NOTHING",
                     (media_id, tag_id))


def thumb_path(media_id, size: str) -> Path:
    return THUMBS_DIR / thumb_rel_dir(media_id) / f"{size}.webp"


def run_exiftool(path: Path) -> dict:
    # QuickTimeUTC : dates vidéo (stockées en UTC) converties dans le TZ du conteneur, comme les photos
    out = subprocess.run(["exiftool", "-json", "-n", "-api", "QuickTimeUTC", str(path)],
                         capture_output=True, check=True, timeout=60).stdout
    return json.loads(out)[0]


def reverse_geocode(lat: float, lon: float) -> tuple[str, str, str]:
    import reverse_geocoder as rg  # GeoNames cities1000 en mémoire, chargé au 1er appel

    r = rg.search([(lat, lon)], mode=1)[0]
    return r["name"], r["admin1"], r["cc"]


def make_thumbs(src: Path, media_id, kind: str) -> None:
    import pyvips

    out_dir = THUMBS_DIR / thumb_rel_dir(media_id)
    out_dir.mkdir(parents=True, exist_ok=True)
    if kind == "video":
        poster = out_dir / "poster.jpg"
        # filtre `thumbnail` : image représentative parmi les premières, marche aussi sur les vidéos < 1 s.
        # Réduire AVANT de choisir : `thumbnail` garde ses N images en mémoire (100 images 4K = plus d'1 Go).
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-threads", "1", "-i", str(src), "-vf",
                        "scale=1440:1440:force_original_aspect_ratio=decrease,thumbnail=12",
                        "-frames:v", "1", str(poster)], check=True, timeout=300)
        src = poster
    for name, size in THUMB_SIZES.items():
        # thumbnail() applique l'orientation EXIF et ne décode que ce qui est nécessaire
        img = pyvips.Image.thumbnail(str(src), size, height=size, size="down")
        img.webpsave(str(out_dir / f"{name}.webp"), Q=80, strip=True)


def _ingest(media_id: str) -> str | None:
    src = QUARANTINE_DIR / media_id
    with db() as conn:
        m = conn.execute("SELECT owner_id, sha256, kind, original_name, storage_path, taken_at "
                         "FROM media WHERE id = %s", (media_id,)).fetchone()
        if m is None:
            return None  # supprimé entre-temps
        rel = m["storage_path"]
        if rel is None:  # sinon : relivraison, les métadonnées sont déjà en base
            with open(src, "rb") as fh:  # frontière de confiance : le hash vient du client
                if hashlib.file_digest(fh, "sha256").digest() != m["sha256"]:
                    raise HashMismatch(media_id)
            exif = run_exiftool(src)
            f = extract_fields(exif)
            # EXIF, sinon date du nom de fichier, sinon date du fichier (import) / du téléphone, sinon maintenant
            f["taken_at"] = f["taken_at"] or date_from_name(m["original_name"]) or m["taken_at"] or datetime.now(timezone.utc)
            if f["latitude"] is not None:
                f["city"], f["admin1"], f["country_code"] = reverse_geocode(f["latitude"], f["longitude"])
            rel = storage_rel_path(m["owner_id"], f["taken_at"], m["sha256"].hex(), m["original_name"])
            sets = ", ".join(f"{k} = %({k})s" for k in f)  # clés fixes de extract_fields, pas d'entrée client
            conn.execute(
                f"UPDATE media SET {sets}, exif = %(exif)s::jsonb, storage_path = %(rel)s WHERE id = %(id)s",
                # \u0000 est refusé par jsonb ; certains fabricants en mettent dans les champs texte
                {**f, "exif": json.dumps(exif).replace("\\u0000", ""), "rel": rel, "id": media_id},
            )
        dest = PHOTOS_DIR / rel
        if src.exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(src, dest)
        make_thumbs(dest, media_id, m["kind"])
        conn.execute("UPDATE media SET status = 'ready', error = NULL WHERE id = %s", (media_id,))
    return m["kind"]


@app.task(bind=True, max_retries=5)
def ingest(self, media_id: str):
    try:
        kind = _ingest(media_id)
    except HashMismatch:
        # Fichier corrompu en transit : on efface tout pour que check-hashes renvoie "absent" et que le client renvoie.
        (QUARANTINE_DIR / media_id).unlink(missing_ok=True)
        with db() as conn:
            conn.execute("DELETE FROM media WHERE id = %s", (media_id,))
        return
    except Exception as exc:
        if self.request.retries < self.max_retries:  # NAS indisponible, timeout…
            raise self.retry(exc=exc, countdown=30 * 2 ** self.request.retries)
        with db() as conn:
            conn.execute("UPDATE media SET status = 'error', error = %s WHERE id = %s",
                         (f"{type(exc).__name__}: {exc}"[:2000], media_id))
        raise
    if kind is None:
        return
    clip_embed.delay(media_id)
    if kind == "image":
        detect_faces.delay(media_id)


@app.task(ignore_result=False)
def embed_query(text: str) -> list[float]:
    """Encodage CLIP d'une recherche : fait par le worker pour ne garder qu'une copie du modèle en RAM."""
    import ml

    return ml.embed_text(text).tolist()


@app.task(autoretry_for=(OSError,), retry_backoff=True, max_retries=3)
def clip_embed(media_id: str):
    import ml

    vec = ml.embed_image(thumb_path(media_id, "md"))
    with db() as conn, conn.transaction():
        conn.execute("INSERT INTO embeddings (media_id, model, embedding) VALUES (%s, %s, %s) "
                     "ON CONFLICT (media_id, model) DO UPDATE SET embedding = EXCLUDED.embedding",
                     (media_id, ml.CLIP_MODEL_ID, vec))
        store_categories(conn, media_id, ml.categorize_many(vec[None, :])[0])
    _store_quality(media_id)


def _store_quality(media_id: str) -> None:
    import enhance as fx

    sharpness, brightness = fx.quality(thumb_path(media_id, "md"))
    with db() as conn:
        conn.execute("UPDATE media SET sharpness = %s, brightness = %s WHERE id = %s", (sharpness, brightness, media_id))


@app.task
def quality_all():
    """Netteté et luminosité des photos déjà importées (relit seulement les miniatures)."""
    with db() as conn:
        ids = [str(r["id"]) for r in conn.execute(
            "SELECT id FROM media WHERE status = 'ready' AND kind = 'image' AND sharpness IS NULL").fetchall()]
    done = 0
    for media_id in ids:
        try:
            _store_quality(media_id)
            done += 1
        except OSError:
            pass  # miniature absente : sera calculée au prochain passage d'analyse
    return done


@app.task(ignore_result=False)
def category_stats():
    """Diagnostic de calibrage : répartition des scores CLIP (exécuté dans le worker, où le modèle est déjà chargé)."""
    import numpy as np

    import ml

    with db() as conn:
        rows = conn.execute("SELECT embedding FROM embeddings WHERE model = %s", (ml.CLIP_MODEL_ID,)).fetchall()
    sims = np.stack([np.asarray(r["embedding"], dtype=np.float32) for r in rows]) @ ml._category_matrix().T
    logits = 100 * sims
    probs = np.exp(logits - logits.max(1, keepdims=True))
    probs /= probs.sum(1, keepdims=True)
    q = lambda a: np.round(np.percentile(a, [10, 50, 90]), 3).tolist()  # noqa: E731
    top = probs.argmax(1)
    names = list(ml.CATEGORIES)
    return {"n": len(rows), "max_sim": q(sims.max(1)), "max_prob": q(probs.max(1)),
            "top_counts": {names[i]: int(c) for i, c in enumerate(np.bincount(top, minlength=len(names))) if c}}


@app.task
def categorize_all():
    """(Re)classe toutes les photos déjà indexées à partir des vecteurs stockés : pas de relecture d'image."""
    import numpy as np

    import ml

    with db() as conn:
        rows = conn.execute("SELECT media_id, embedding FROM embeddings WHERE model = %s", (ml.CLIP_MODEL_ID,)).fetchall()
    for i in range(0, len(rows), 500):
        batch = rows[i:i + 500]
        cats = ml.categorize_many(np.stack([np.asarray(r["embedding"], dtype=np.float32) for r in batch]))
        with db() as conn, conn.transaction():
            for r, names in zip(batch, cats):
                store_categories(conn, r["media_id"], names)
    return len(rows)


@app.task(autoretry_for=(OSError,), retry_backoff=True, max_retries=3)
def detect_faces(media_id: str):
    import ml

    with db() as conn:  # visages corrigés à la main sur cette photo : on ne refait pas la détection
        if conn.execute("SELECT 1 FROM faces WHERE media_id = %s AND manual", (media_id,)).fetchone():
            return
    found = ml.detect_faces(thumb_path(media_id, "md"))
    with db() as conn, conn.transaction():
        owner = conn.execute("SELECT owner_id FROM media WHERE id = %s", (media_id,)).fetchone()["owner_id"]
        family = family_ids(conn, owner)  # une même personne est reconnue sur les photos de toute la famille
        conn.execute("DELETE FROM faces WHERE media_id = %s", (media_id,))  # idempotent
        for bbox, score, emb in found:
            # shortcut: clustering glouton en ligne (plus proche visage connu sous le seuil) ;
            # passer à un re-clustering HDBSCAN périodique si les personnes se mélangent/fragmentent.
            near = conn.execute(
                "SELECT person_id FROM faces WHERE owner_id = ANY(%s) AND person_id IS NOT NULL "
                "AND embedding <=> %s < %s ORDER BY embedding <=> %s LIMIT 1",
                (family, emb, FACE_MAX_DIST, emb),
            ).fetchone()
            person_id = near["person_id"] if near else conn.execute(
                "INSERT INTO people (owner_id) VALUES (%s) RETURNING id", (owner,)).fetchone()["id"]
            conn.execute("INSERT INTO faces (media_id, owner_id, person_id, bbox, det_score, embedding) "
                         "VALUES (%s, %s, %s, %s, %s, %s)", (media_id, owner, person_id, bbox, score, emb))


@app.task(autoretry_for=(vlm.RetryableError,), retry_backoff=60, retry_backoff_max=3600,
          retry_jitter=True, max_retries=6, rate_limit=VLM_RATE_LIMIT)
def describe(media_id: str):
    # Après max_retries (quota du jour épuisé), vlm_done_at reste NULL : sweep_vlm reprendra le média demain.
    r = vlm.describe(thumb_path(media_id, "md"))
    tags = {t.strip().lower()[:64] for t in r.tags if t.strip()}
    with db() as conn, conn.transaction():
        conn.execute("UPDATE media SET description = %s, ocr_text = %s, vlm_done_at = now() WHERE id = %s",
                     (r.description[:4000], r.ocr_text[:8000] or None, media_id))
        conn.execute("DELETE FROM media_tags WHERE media_id = %s AND source = 'vlm'", (media_id,))
        for name in list(tags)[:20]:
            tag_id = conn.execute("INSERT INTO tags (name) VALUES (%s) ON CONFLICT (name) "
                                  "DO UPDATE SET name = EXCLUDED.name RETURNING id", (name,)).fetchone()["id"]
            conn.execute("INSERT INTO media_tags (media_id, tag_id, source) VALUES (%s, %s, 'vlm') "
                         "ON CONFLICT DO NOTHING", (media_id, tag_id))


@app.task
def sweep_vlm():
    """Seul point d'entrée de la queue vlm : un budget horaire borne la consommation du quota gratuit."""
    if not vlm.ENABLED:
        return
    with db() as conn:
        rows = conn.execute(
            "UPDATE media SET vlm_attempted_at = now() WHERE id IN ("
            " SELECT id FROM media WHERE status = 'ready' AND deleted_at IS NULL AND vlm_done_at IS NULL"
            " AND (vlm_attempted_at IS NULL OR vlm_attempted_at < now() - interval '1 day')"
            " ORDER BY taken_at DESC LIMIT %s) RETURNING id",
            (VLM_HOURLY_BUDGET,),
        ).fetchall()
    for row in rows:
        describe.delay(str(row["id"]))


def iter_media(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if visible_dir(d, IMPORT_EXCLUDE))
        for name in sorted(filenames):
            if media_mime(name):
                yield Path(dirpath) / name


def display_name(name: str) -> str:
    """Noms non UTF-8 du NAS (ex. « canapé » en Latin-1, lu avec un caractère de substitution) : PostgreSQL les refuse."""
    raw = os.fsencode(name)
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp1252", errors="replace")


assert display_name(os.fsdecode(b"canap\xe9.jpg")) == "canapé.jpg"


def _import_file(owner_id, path: Path) -> str:
    """Copie un fichier en quarantaine et l'envoie dans le pipeline normal (vérif hash, EXIF, miniatures, IA)."""
    st = path.stat()
    # Ré-import d'un dossier : même nom + même taille au octet près = déjà importé, sans relire tout le fichier
    # sur le NAS (sinon ~5 fichiers/min). shortcut: deux fichiers différents de même nom et taille exacte seraient
    # confondus ; quasi impossible pour des photos/vidéos.
    with db() as conn:
        if conn.execute("SELECT 1 FROM media WHERE owner_id = %s AND original_name = %s AND size_bytes = %s LIMIT 1",
                        (owner_id, display_name(path.name), st.st_size)).fetchone():
            return "skipped"
    with open(path, "rb") as fh:
        sha = hashlib.file_digest(fh, "sha256").digest()
    mime = media_mime(path.name)
    with db() as conn:
        if conn.execute("SELECT 1 FROM media WHERE owner_id = %s AND sha256 = %s", (owner_id, sha)).fetchone():
            return "skipped"
        tmp = None
        with conn.transaction():
            row = conn.execute(
                "INSERT INTO media (owner_id, sha256, kind, mime_type, original_name, size_bytes, taken_at) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s) ON CONFLICT (owner_id, sha256) DO NOTHING RETURNING id",
                (owner_id, sha, "video" if mime.startswith("video/") else "image", mime, display_name(path.name), st.st_size,
                 # repli si pas d'EXIF : date du fichier, en heure locale « comme si » UTC (cf. metadata.py)
                 datetime.fromtimestamp(st.st_mtime).replace(tzinfo=timezone.utc)),
            ).fetchone()
            if row is None:
                return "skipped"
            media_id = str(row["id"])
            try:
                tmp = QUARANTINE_DIR / f"{media_id}.importing"
                shutil.copyfile(path, tmp)  # la source n'est jamais modifiée
                os.replace(tmp, QUARANTINE_DIR / media_id)
            except OSError:
                if tmp:
                    tmp.unlink(missing_ok=True)
                raise  # annule l'INSERT
    ingest.delay(media_id)
    return "imported"


@app.task
def import_folder(job_id: str):
    # Un seul exécutant par import : un message relivré (crash, redémarrage) occupait sinon un 2e thread
    # sur le même dossier, et plus rien d'autre n'était traité. Le verrou expire si le worker meurt.
    lock = f"import:lock:{job_id}"
    if not _redis.set(lock, 1, nx=True, ex=900):
        return
    try:
        _import_folder(job_id, lock)
    finally:
        _redis.delete(lock)


def _import_folder(job_id: str, lock: str):
    with db() as conn:
        job = conn.execute("UPDATE import_jobs SET status = 'running', started_at = coalesce(started_at, now()) "
                           "WHERE id = %s RETURNING owner_id, path", (job_id,)).fetchone()
    if job is None:
        return
    counts = {"found": 0, "imported": 0, "skipped": 0, "failed": 0}

    def save(status: str | None = None, error: str | None = None):
        _redis.expire(lock, 900)  # toujours vivant : on garde le verrou
        with db() as conn:
            conn.execute(
                "UPDATE import_jobs SET found = %(found)s, imported = %(imported)s, skipped = %(skipped)s, "
                "failed = %(failed)s, status = coalesce(%(status)s, status), error = %(error)s, "
                "finished_at = CASE WHEN %(status)s IS NULL THEN NULL ELSE now() END WHERE id = %(id)s",
                {**counts, "status": status, "error": error, "id": job_id})

    try:
        for path in iter_media(safe_join(IMPORT_ROOT, job["path"])):
            counts["found"] += 1
            try:
                counts[_import_file(job["owner_id"], path)] += 1
            except Exception as exc:  # fichier illisible, nom bizarre, NAS indisponible… on continue avec les autres
                print(f"import {path!r}: {exc!r}")
                counts["failed"] += 1
            if counts["found"] % 20 == 0:
                save()
        save("done")
    except Exception as exc:
        save("error", f"{type(exc).__name__}: {exc}"[:500])
        raise


@app.task
def enhance(media_id: str, user_id: str, mode: str = "auto"):
    """Crée une copie retouchée (l'original n'est jamais modifié), qui suit ensuite le pipeline normal."""
    import uuid

    import pyvips

    import enhance as fx

    with db() as conn:
        m = conn.execute("SELECT storage_path, original_name, taken_at, kind FROM media WHERE id = %s",
                         (media_id,)).fetchone()
    if m is None or m["kind"] != "image" or not m["storage_path"]:
        return None
    src = PHOTOS_DIR / m["storage_path"]
    tmp = QUARANTINE_DIR / f"enh-{uuid.uuid4()}.jpg"
    try:
        if mode == "gemini":
            # 2048 px max envoyés : suffisant pour la retouche, bien moins de données transmises
            preview = pyvips.Image.thumbnail(str(src), 2048, height=2048, size="down").jpegsave_buffer(Q=92)
            pyvips.Image.new_from_buffer(vlm.gemini_enhance(preview), "").jpegsave(str(tmp), Q=92)
        else:
            fx.save_jpeg(fx.enhance_rgb(fx.load_rgb(src)), tmp)
        # date, GPS, appareil repris de l'original ; l'image est déjà redressée, d'où Orientation=1
        subprocess.run(["exiftool", "-q", "-overwrite_original", "-TagsFromFile", str(src), "-all:all",
                        "-Orientation#=1", str(tmp)], check=False, timeout=60)
        with open(tmp, "rb") as fh:
            sha = hashlib.file_digest(fh, "sha256").digest()
        suffix = "gemini" if mode == "gemini" else "ameliore"
        with db() as conn:
            row = conn.execute(
                "INSERT INTO media (owner_id, sha256, kind, mime_type, original_name, size_bytes, taken_at, edited_from) "
                "VALUES (%s, %s, 'image', 'image/jpeg', %s, %s, %s, %s) ON CONFLICT (owner_id, sha256) DO NOTHING "
                "RETURNING id",
                (user_id, sha, f"{Path(m['original_name']).stem}_{suffix}.jpg", tmp.stat().st_size,
                 m["taken_at"], media_id)).fetchone()
        if row is None:
            return None  # déjà retouchée à l'identique
        new_id = str(row["id"])
        os.replace(tmp, QUARANTINE_DIR / new_id)
        ingest.delay(new_id)
        return new_id
    finally:
        tmp.unlink(missing_ok=True)


IMPORT_BACKLOG_PAUSE = int(os.environ.get("IMPORT_BACKLOG_PAUSE", "50"))


def ml_gate() -> bool:
    """VM 2 Go : import NAS + modèles IA en même temps = mémoire saturée (VM figée le 9/11).
    Tant que des fichiers importés attendent leur traitement, la file `ml` est en pause
    (les tâches restent dans Redis) ; elle reprend toute seule ensuite. Retourne True si en pause."""
    with db() as conn:
        busy = conn.execute(
            # file d'attente seulement : un import tué (OOM) resterait « running » et bloquerait l'IA pour toujours
            "SELECT (SELECT count(*) FROM media WHERE status = 'quarantined') > %s AS busy",
            (IMPORT_BACKLOG_PAUSE,)).fetchone()["busy"]
    if busy:
        app.control.cancel_consumer("ml")
    else:
        app.control.add_consumer("ml")
    return busy


@worker_ready.connect
def _gate_on_start(**_):
    # au démarrage le worker écoute toutes les files : on remet la pause tout de suite si besoin
    try:
        ml_gate()
    except Exception:  # base pas encore prête : housekeeping (15 min) refera le point
        pass


# Animaux nommés : pas de modèle de reconnaissance individuelle en local ; on garde les photos « Animaux » qui
# ressemblent (CLIP) à une photo d'exemple. Seuil calibré sur un teckel réel : 0.72 = encore lui, 0.70 = un écureuil.
PET_MIN_SIM = float(os.environ.get("PET_MIN_SIM", "0.78"))


def refresh_pet_media(conn, pet_id=None) -> None:
    """Recalcule pet_media (tous les animaux, ou un seul) : ~0.2 s par photo d'exemple."""
    from ml import CLIP_MODEL_ID

    p = {"pet": pet_id, "model": CLIP_MODEL_ID, "sim": PET_MIN_SIM}
    conn.execute("DELETE FROM pet_media WHERE %(pet)s::uuid IS NULL OR pet_id = %(pet)s", p)
    conn.execute(
        "INSERT INTO pet_media (pet_id, media_id) "
        "SELECT p.id, ex FROM pets p, unnest(p.examples) ex WHERE %(pet)s::uuid IS NULL OR p.id = %(pet)s "
        "UNION SELECT p.id, a.media_id FROM pets p "
        "JOIN embeddings x ON x.media_id = ANY(p.examples) AND x.model = %(model)s "
        # OFFSET 0 : d'abord les ~1500 photos « Animaux », puis les distances (sinon toute la table embeddings)
        "JOIN (SELECT e.media_id, e.embedding FROM tags t JOIN media_tags mt ON mt.tag_id = t.id AND mt.source = 'clip' "
        "      JOIN embeddings e ON e.media_id = mt.media_id AND e.model = %(model)s WHERE t.name = 'Animaux' OFFSET 0) a "
        "  ON 1 - (a.embedding <=> x.embedding) >= %(sim)s "
        "WHERE %(pet)s::uuid IS NULL OR p.id = %(pet)s ON CONFLICT DO NOTHING", p)


@app.task
def housekeeping():
    """Purge les uploads abandonnés ; relance les ingests et les analyses jamais abouties (crash, Redis down)."""
    from ml import CLIP_MODEL_ID

    with db() as conn:
        # Sans vecteur CLIP (du modèle courant), un média est invisible à la recherche : on relance son analyse
        unindexed = conn.execute(
            "SELECT m.id, m.kind FROM media m WHERE m.status = 'ready' AND m.deleted_at IS NULL "
            "AND m.created_at < now() - interval '1 hour' AND NOT EXISTS "
            "(SELECT 1 FROM embeddings e WHERE e.media_id = m.id AND e.model = %s) LIMIT 200",
            (CLIP_MODEL_ID,)).fetchall()
        expired = conn.execute("DELETE FROM upload_sessions WHERE expires_at < now() RETURNING id").fetchall()
        # 1 h > durée max des retries d'ingest (~15 min) : ces médias ne sont plus en cours de traitement
        stuck = conn.execute("SELECT id FROM media WHERE status = 'quarantined' "
                             "AND created_at < now() - interval '1 hour'").fetchall()
        refresh_pet_media(conn)  # nouvelles photos de chaque animal nommé
    for row in expired:
        part_path(row["id"]).unlink(missing_ok=True)
    # Une seule relance toutes les 6 h par média : sinon, file engorgée, chaque passage (15 min) ajoutait des doublons
    for row in stuck:
        if once_per(f"requeue:ingest:{row['id']}", 6 * 3600):
            ingest.delay(str(row["id"]))
    if ml_gate():
        return  # import en cours : les analyses IA attendront (relancées au passage suivant)
    for row in unindexed:
        if once_per(f"requeue:ml:{row['id']}", 6 * 3600):
            clip_embed.delay(str(row["id"]))
            if row["kind"] == "image":
                detect_faces.delay(str(row["id"]))


# ---------- Vidéo souvenir du mois ----------
MUSIC_DIR = Path(os.environ.get("MUSIC_DIR", str(IMPORT_ROOT / "musique")))
MEMORY_VIDEO_DIR = THUMBS_DIR / "memories"


def family_key(conn, user_id):
    """Une vidéo par famille : identifiant stable = plus petit id de ses membres."""
    return min(family_ids(conn, user_id), key=str)


@app.task
def make_memory_video(video_id: str):
    from datetime import date

    import memvideo

    with db() as conn:
        v = conn.execute("UPDATE memory_videos SET status = 'running', error = NULL, updated_at = now() "
                         "WHERE id = %s RETURNING owner_id, month", (video_id,)).fetchone()
        if v is None:
            return
        fam = family_ids(conn, v["owner_id"])
        month: date = v["month"]
        rows = conn.execute(
            "SELECT m.id, m.kind, m.storage_path, m.duration_ms, m.sharpness, m.taken_at, "
            "       (SELECT count(*) FROM faces f WHERE f.media_id = m.id AND f.person_id IS NOT NULL) AS faces "
            "FROM media m WHERE m.owner_id = ANY(%(fam)s) AND m.status = 'ready' AND m.deleted_at IS NULL "
            "AND m.taken_at >= %(start)s AND m.taken_at < %(start)s + interval '1 month' AND m.edited_from IS NULL "
            "AND (m.kind = 'video' AND m.duration_ms >= 3000 OR m.kind = 'image' "
            "     AND coalesce(m.sharpness, 100) >= 40 AND coalesce(m.brightness, 128) BETWEEN 28 AND 242) "
            "AND NOT EXISTS (SELECT 1 FROM media_tags mt JOIN tags t ON t.id = mt.tag_id WHERE mt.media_id = m.id "
            "                AND mt.source = 'clip' AND t.name IN ('Captures d''écran', 'Documents')) "
            "ORDER BY m.taken_at", {"fam": fam, "start": month}).fetchall()

    def done(status, **fields):
        with db() as conn:
            conn.execute("UPDATE memory_videos SET status = %(s)s, media_ids = %(ids)s, cover_media_id = %(cover)s, "
                         "duration_s = %(dur)s, error = %(err)s, updated_at = now() WHERE id = %(id)s",
                         {"s": status, "id": video_id, "ids": fields.get("ids"), "cover": fields.get("cover"),
                          "dur": fields.get("dur"), "err": fields.get("err")})

    photos = memvideo.spread_pick([r for r in rows if r["kind"] == "image"], memvideo.MAX_PHOTOS, memvideo.photo_score)
    videos = memvideo.spread_pick([r for r in rows if r["kind"] == "video"], memvideo.MAX_VIDEOS, lambda r: r["duration_ms"])
    chosen = sorted(photos + videos, key=lambda r: r["taken_at"])
    if len(chosen) < memvideo.MIN_ITEMS:
        return done("empty", ids=[r["id"] for r in chosen])
    clips = [("video", PHOTOS_DIR / r["storage_path"], r["duration_ms"] / 1000) if r["kind"] == "video"
             else ("image", thumb_path(r["id"], "md"), 0) for r in chosen]
    cover = max(photos, key=memvideo.photo_score)["id"] if photos else chosen[0]["id"]
    try:
        dur = memvideo.render(month, clips, MEMORY_VIDEO_DIR / f"{video_id}.mp4", memvideo.pick_music(MUSIC_DIR))
    except Exception as exc:
        done("error", err=f"{type(exc).__name__}: {exc}"[:500])
        raise
    done("ready", ids=[r["id"] for r in chosen], cover=cover, dur=dur)


def queue_memory_video(conn, user_id, month) -> dict:
    """Crée (ou relance) la vidéo du mois pour la famille de user_id."""
    row = conn.execute(
        "INSERT INTO memory_videos (owner_id, month) VALUES (%s, %s) "
        "ON CONFLICT (owner_id, month) DO UPDATE SET status = 'queued', error = NULL, updated_at = now() "
        "RETURNING id, to_char(month, 'YYYY-MM') AS month, status", (family_key(conn, user_id), month)).fetchone()
    make_memory_video.delay(str(row["id"]))
    return row


@app.task
def monthly_memory_videos():
    """Le 1er du mois : vidéo du mois écoulé pour chaque famille (une seule fois)."""
    from datetime import date

    today = date.today()
    prev = date(today.year - (today.month == 1), (today.month - 2) % 12 + 1, 1)
    with db() as conn:
        users = conn.execute("SELECT id FROM users WHERE email <> 'e2e@test.local'").fetchall()
        for key in {family_key(conn, u["id"]) for u in users}:
            if not conn.execute("SELECT 1 FROM memory_videos WHERE owner_id = %s AND month = %s", (key, prev)).fetchone():
                queue_memory_video(conn, key, prev)
