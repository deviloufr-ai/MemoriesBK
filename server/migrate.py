"""Migrations SQL (db/migrations/NNN_*.sql) appliquées au démarrage de l'API, une seule fois chacune.

    python migrate.py            -> applique ce qui manque et affiche la liste
"""
import os
import sys
from pathlib import Path

import psycopg

MIGRATIONS_DIR = Path(os.environ.get("MIGRATIONS_DIR", "/migrations"))
# Base créée avant ce suivi : 002 à 015 avaient été appliquées à la main (psql). Signe : la colonne ajoutée par 015.
BOOTSTRAP_UNTIL = "015"


def run(dsn: str, folder: Path = MIGRATIONS_DIR) -> list[str]:
    """Applique les fichiers non encore enregistrés, dans l'ordre ; renvoie ceux appliqués."""
    if not folder.is_dir():
        return []
    files = sorted(folder.glob("[0-9][0-9][0-9]_*.sql"))
    applied = []
    with psycopg.connect(dsn, autocommit=True) as c:
        c.execute("SELECT pg_advisory_lock(727001)")  # deux API qui démarrent ensemble : une seule migre
        try:
            if c.execute("SELECT to_regclass('schema_migrations')").fetchone()[0] is None:
                c.execute("CREATE TABLE schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
                legacy = c.execute("SELECT 1 FROM information_schema.columns WHERE table_name = 'family_share_pets' "
                                   "AND column_name = 'from_year'").fetchone()
                if legacy:
                    for f in files:
                        if f.name[:3] <= BOOTSTRAP_UNTIL:
                            c.execute("INSERT INTO schema_migrations (name) VALUES (%s)", (f.name,))
            done = {r[0] for r in c.execute("SELECT name FROM schema_migrations")}
            for f in files:
                if f.name in done:
                    continue
                with c.transaction():  # fichier entier ou rien
                    c.execute(f.read_text(encoding="utf-8"))
                    c.execute("INSERT INTO schema_migrations (name) VALUES (%s)", (f.name,))
                applied.append(f.name)
        finally:
            c.execute("SELECT pg_advisory_unlock(727001)")
    return applied


if __name__ == "__main__":
    from tasks import DSN

    print("appliquées :", run(DSN, Path(sys.argv[1]) if sys.argv[1:] else MIGRATIONS_DIR) or "rien")
