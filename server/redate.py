"""Recalcule la date des médias déjà traités avec la règle actuelle (EXIF plausible, sinon nom de fichier).
Usage (conteneur worker) : python redate.py [--dry-run]. Ne touche qu'aux dates fausses : un écart de plus d'un jour."""
import sys
from datetime import timedelta

from metadata import date_from_name, parse_exif_date
from tasks import db

dry = "--dry-run" in sys.argv
changed = 0
with db() as conn:
    rows = conn.execute("SELECT id, original_name, taken_at, exif FROM media "
                        "WHERE status = 'ready'").fetchall()
    for r in rows:
        new = parse_exif_date(r["exif"] or {}) or date_from_name(r["original_name"])
        if new is None or abs(new - r["taken_at"]) <= timedelta(days=1):
            continue
        changed += 1
        if changed <= 10:
            print(f"{r['original_name']}: {r['taken_at']:%Y-%m-%d} -> {new:%Y-%m-%d}")
        if not dry:  # storage_path garde l'ancien dossier daté : sans effet, le chemin est stocké tel quel
            conn.execute("UPDATE media SET taken_at = %s WHERE id = %s", (new, r["id"]))
print(f"{changed} date(s) {'à corriger' if dry else 'corrigée(s)'} sur {len(rows)}")
