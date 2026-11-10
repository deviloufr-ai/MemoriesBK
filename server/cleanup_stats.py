"""Calibrage du nettoyage : nombre de suggestions et temps de calcul pour un compte.

    docker compose exec -T api python cleanup_stats.py <email>
"""
import sys
import time

import api
from tasks import db

with db() as c:
    uid = c.execute("SELECT id FROM users WHERE lower(email) = lower(%s)", (sys.argv[1],)).fetchone()["id"]
fam = api.current_family(uid)
for kind in ("blurry", "dark", "screenshots", "duplicates"):
    t = time.time()
    items = api.cleanup(kind, fam, None)["items"]
    picked = sum(1 for i in items if i["suggested"])
    print(f"{kind}: {len(items)} éléments, {picked} présélectionnés, {time.time() - t:.1f} s")
