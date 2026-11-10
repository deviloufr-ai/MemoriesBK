"""Fonctions pures (aucune dépendance lourde) : champs EXIF -> colonnes `media`, chemin de stockage."""
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath

_DATE_KEYS = ("DateTimeOriginal", "CreateDate", "MediaCreateDate")
# 20170303_233116.jpg, IMG_20201224_152809.jpg, VID-20260825-WA0000.mp4, Screenshot_20240101-101010.png,
# video-2011-09-07-22-11-25.mp4, PXL_20230101_120000123.jpg…
_NAME_DATE = re.compile(r"(?<!\d)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])"
                        r"(?:[-_ T]?([01]\d|2[0-3])[-:.]?([0-5]\d)[-:.]?([0-5]\d))?(?!\d)")
_NAME_EPOCH_MS = re.compile(r"(?<!\d)(1\d{12})(?!\d)")


def _plausible(dt: datetime) -> bool:
    # Vidéos Samsung/QuickTime : date « 0 » lue comme 1904, ou décalée de 66 ans (1946…) ; jamais avant 1971
    return 1971 <= dt.year and dt <= datetime.now(timezone.utc) + timedelta(days=1)


def parse_exif_date(exif: dict) -> datetime | None:
    for key in _DATE_KEYS:
        raw = exif.get(key)
        if not isinstance(raw, str) or raw.startswith("0000"):
            continue
        try:
            dt = datetime.strptime(raw[:19], "%Y:%m:%d %H:%M:%S")
        except ValueError:
            continue
        # shortcut: heure locale stockée "comme si" UTC pour grouper par jour local ;
        # ajouter une colonne offset si l'heure absolue devient nécessaire.
        dt = dt.replace(tzinfo=timezone.utc)
        if _plausible(dt):
            return dt
    return None


def date_from_name(name: str) -> datetime | None:
    """Date dans le nom de fichier (appareils photo, WhatsApp, captures) : repli quand l'EXIF n'a pas de date."""
    m = _NAME_DATE.search(name or "")
    if not m:
        # FB_IMG_1776982127173.jpg, 1767428388314.jpg (Messenger…) : horodatage Unix en millisecondes
        ms = _NAME_EPOCH_MS.search(name or "")
        dt = datetime.fromtimestamp(int(ms.group(1)) / 1000, timezone.utc) if ms else None
        return dt if dt and _plausible(dt) and dt.year >= 2005 else None
    y, mo, d, hh, mi, ss = (int(g) if g else 0 for g in m.groups())
    try:
        dt = datetime(y, mo, d, hh, mi, ss, tzinfo=timezone.utc)
    except ValueError:  # 20230231
        return None
    return dt if _plausible(dt) else None


def _gps(exif: dict) -> tuple[float | None, float | None]:
    lat, lon = exif.get("GPSLatitude"), exif.get("GPSLongitude")
    if not (isinstance(lat, (int, float)) and isinstance(lon, (int, float))):
        return None, None
    # Selon le tag retenu par exiftool, la valeur est signée ou non : on n'applique la Ref que si besoin.
    if exif.get("GPSLatitudeRef") == "S" and lat > 0:
        lat = -lat
    if exif.get("GPSLongitudeRef") == "W" and lon > 0:
        lon = -lon
    if (lat, lon) == (0, 0) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None, None
    return float(lat), float(lon)


def _str(v) -> str | None:
    return (str(v).strip()[:100] or None) if v is not None else None


def extract_fields(exif: dict) -> dict:
    """`exif` = sortie de `exiftool -json -n` pour un fichier."""
    w, h = exif.get("ImageWidth"), exif.get("ImageHeight")
    if exif.get("Orientation") in (5, 6, 7, 8) or exif.get("Rotation") in (90, 270):
        w, h = h, w
    lat, lon = _gps(exif)
    dur = exif.get("Duration")
    return {
        "taken_at": parse_exif_date(exif),
        "width": w if isinstance(w, int) else None,
        "height": h if isinstance(h, int) else None,
        "duration_ms": round(dur * 1000) if isinstance(dur, (int, float)) else None,
        "camera_make": _str(exif.get("Make")),
        "camera_model": _str(exif.get("Model")),
        "latitude": lat,
        "longitude": lon,
    }


def storage_rel_path(owner_id, taken_at: datetime, sha256_hex: str, original_name: str) -> str:
    # original_name vient du client : seule une extension alphanumérique courte est conservée
    ext = PurePosixPath(original_name).suffix.lower().lstrip(".")
    if not (ext.isalnum() and len(ext) <= 5):
        ext = "bin"
    return f"{owner_id}/{taken_at:%Y/%m/%d}/{sha256_hex}.{ext}"


MEDIA_EXT = {"jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "tif", "tiff", "dng",
             "mp4", "mov", "m4v", "3gp", "avi", "mkv", "webm"}
_MIME = {"heic": "image/heic", "heif": "image/heif", "dng": "image/x-adobe-dng", "mkv": "video/x-matroska",
         "3gp": "video/3gpp", "m4v": "video/x-m4v", "webm": "video/webm", "jpg": "image/jpeg",
         "jpeg": "image/jpeg", "png": "image/png", "webp": "image/webp", "gif": "image/gif",
         "tif": "image/tiff", "tiff": "image/tiff", "mp4": "video/mp4", "mov": "video/quicktime",
         "avi": "video/x-msvideo"}


def media_mime(name: str) -> str | None:
    """Type MIME d'un fichier média d'après son extension ; None si ce n'est pas un média géré."""
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    return _MIME.get(ext) if ext in MEDIA_EXT and not name.startswith(".") else None


def visible_dir(name: str, excluded: set[str]) -> bool:
    """Dossiers ignorés à l'import : cachés, systèmes NAS (@eaDir, #recycle) et la bibliothèque elle-même."""
    return not name.startswith((".", "@", "#")) and name not in excluded


def safe_join(root: Path, rel: str) -> Path:
    """Chemin fourni par le client, borné à `root` (pas de ../ ni de lien symbolique vers l'extérieur)."""
    root = root.resolve()
    p = (root / rel.strip().lstrip("/\\")).resolve()
    if p != root and root not in p.parents:
        raise ValueError(f"chemin hors de la racine d'import : {rel!r}")
    return p


def thumb_rel_dir(media_id) -> str:
    s = str(media_id)
    return f"{s[:2]}/{s}"
