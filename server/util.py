"""Helpers purs de l'API (testés dans test_util.py)."""
import base64
import hashlib
import hmac
import re
import secrets
from datetime import datetime
from uuid import UUID

_SCRYPT = {"n": 2**14, "r": 8, "p": 1}


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    return f"scrypt${salt.hex()}${hashlib.scrypt(password.encode(), salt=salt, **_SCRYPT).hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, salt, digest = stored.split("$")
        computed = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), **_SCRYPT).hex()
    except ValueError:
        return False
    return hmac.compare_digest(computed, digest)


def encode_cursor(taken_at: datetime, media_id) -> str:
    # base64url : un '+' du fuseau horaire en clair deviendrait un espace dans la query string
    return base64.urlsafe_b64encode(f"{taken_at.isoformat()}|{media_id}".encode()).decode()


def decode_cursor(cursor: str) -> tuple[datetime, UUID]:
    """Lève ValueError si le curseur est invalide."""
    ts, mid = base64.urlsafe_b64decode(cursor.encode()).decode().split("|")
    return datetime.fromisoformat(ts), UUID(mid)


_RANGE = re.compile(r"bytes (\d+)-(\d+)/(\d+)")


def parse_content_range(header: str, chunk_max: int) -> tuple[int, int, int]:
    """`bytes start-end/total` (end inclus). Lève ValueError si invalide."""
    m = _RANGE.fullmatch(header.strip())
    if not m:
        raise ValueError("Content-Range attendu : bytes start-end/total")
    start, end, total = map(int, m.groups())
    if end < start or end >= total:
        raise ValueError("plage incohérente")
    if end - start + 1 > chunk_max:
        raise ValueError(f"bloc > {chunk_max} octets")
    return start, end, total
