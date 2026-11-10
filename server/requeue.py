"""Reconstruit les files Celery sans doublons (après un engorgement) : imports en attente puis analyses manquantes.

    docker compose exec -T api python requeue.py [--flush]
"""
import sys

import redis

import tasks

r = redis.Redis.from_url(tasks.REDIS_URL)
print("avant :", {q: r.llen(q) for q in ("query", "media", "ml")})
with tasks.db() as c:
    pending = c.execute("SELECT id FROM media WHERE status = 'quarantined' ORDER BY created_at DESC").fetchall()
    unindexed = c.execute(
        "SELECT m.id, m.kind FROM media m WHERE m.status = 'ready' AND m.deleted_at IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM embeddings e WHERE e.media_id = m.id) ORDER BY m.created_at DESC").fetchall()
if "--flush" in sys.argv:
    r.delete("media", "ml")
for row in pending:  # les plus récents (ajouts manuels) passent en premier
    r.set(f"requeue:ingest:{row['id']}", 1, ex=6 * 3600)
    tasks.ingest.apply_async(args=[str(row["id"])], queue="media")
for row in unindexed:
    r.set(f"requeue:ml:{row['id']}", 1, ex=6 * 3600)
    tasks.clip_embed.delay(str(row["id"]))
    if row["kind"] == "image":
        tasks.detect_faces.delay(str(row["id"]))
print("après :", {q: r.llen(q) for q in ("query", "media", "ml")},
      f"| imports en attente : {len(pending)} | photos à analyser : {len(unindexed)}")
