"""Test de bout en bout contre une stack lancée (docker compose up).

    docker compose exec -T api python e2e_test.py <email> <mot de passe>

Crée une photo avec EXIF (date + GPS Paris), l'envoie en 3 blocs avec une reprise simulée,
attend le pipeline (ingest, miniatures, CLIP) puis vérifie dédup, timeline, recherche et fichiers.
"""
import hashlib
import io
import subprocess
import sys
import tempfile
import time

import httpx
from PIL import Image, ImageDraw

BASE = "http://localhost:8080/api/v1"


def make_photo() -> bytes:
    img = Image.new("RGB", (1600, 1200), (255, 140, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 700, 1600, 1200], fill=(30, 90, 200))  # « mer » sous un ciel orange
    d.ellipse([650, 450, 950, 750], fill=(255, 230, 80))  # « soleil »
    d.text((10, 10), str(time.time_ns()), fill=(255, 255, 255))  # hash unique : le test est rejouable
    with tempfile.NamedTemporaryFile(suffix=".jpg") as f:
        img.save(f.name, quality=90)
        subprocess.run(["exiftool", "-q", "-overwrite_original", "-DateTimeOriginal=2024:07:14 21:30:05",
                        "-GPSLatitude=48.8584", "-GPSLatitudeRef=N", "-GPSLongitude=2.2945",
                        "-GPSLongitudeRef=E", "-Make=Test", "-Model=E2E", f.name], check=True)
        return open(f.name, "rb").read()


def main(email: str, password: str) -> None:
    c = httpx.Client(timeout=60)
    token = c.post(f"{BASE}/auth/login", json={"email": email, "password": password}).raise_for_status().json()
    c.headers["Authorization"] = f"Bearer {token['access_token']}"
    assert c.get(f"{BASE}/media/timeline", headers={"Authorization": "Bearer faux"}).status_code == 401

    data = make_photo()
    sha = hashlib.sha256(data).hexdigest()
    assert c.post(f"{BASE}/sync/check-hashes", json={"hashes": [sha]}).json()["existing"] == {}

    meta = {"sha256": sha, "size": len(data), "filename": "IMG_E2E.JPG", "mime_type": "image/jpeg"}
    init = c.post(f"{BASE}/uploads", json=meta).raise_for_status().json()
    uid, size = init["upload_id"], len(data)
    third = size // 3

    def put(start: int, end: int):
        return c.put(f"{BASE}/uploads/{uid}", content=data[start:end + 1],
                     headers={"Content-Range": f"bytes {start}-{end}/{size}"})

    assert put(0, third - 1).json() == {"offset": third}
    # Reprise : un nouvel init renvoie la même session et l'offset serveur
    again = c.post(f"{BASE}/uploads", json=meta).json()
    assert (again["upload_id"], again["offset"]) == (uid, third), again
    assert put(0, third - 1).status_code == 409  # bloc déjà reçu
    assert c.post(f"{BASE}/uploads/{uid}/complete").status_code == 409  # incomplet
    put(third, 2 * third - 1).raise_for_status()
    put(2 * third, size - 1).raise_for_status()
    done = c.post(f"{BASE}/uploads/{uid}/complete")
    assert done.status_code == 202, done.text
    media_id = done.json()["media_id"]
    print("upload ok", media_id)

    # Dédup
    assert c.post(f"{BASE}/uploads", json=meta).json() == {"status": "exists", "media_id": media_id}

    # Pipeline : apparition dans la timeline
    for _ in range(120):
        items = c.get(f"{BASE}/media/timeline").json()["items"]
        if any(i["id"] == media_id for i in items):
            break
        time.sleep(1)
    else:
        sys.exit("échec : média jamais passé à ready (voir docker compose logs worker-media)")
    item = next(i for i in items if i["id"] == media_id)
    assert item["taken_at"].startswith("2024-07-14T21:30:05"), item
    assert (item["width"], item["height"]) == (1600, 1200), item
    print("ingest ok", item)

    thumb = c.get(f"{BASE}/media/{media_id}/thumb/sm")
    assert thumb.headers["content-type"] == "image/webp" and max(Image.open(io.BytesIO(thumb.content)).size) == 320
    rng = c.get(f"{BASE}/media/{media_id}/original", headers={"Range": "bytes=0-99"})
    assert rng.status_code == 206 and len(rng.content) == 100
    assert c.get(f"{BASE}/media/00000000-0000-0000-0000-000000000000/original").status_code == 404
    print("fichiers ok")

    # CLIP (le 1er appel télécharge le modèle : jusqu'à quelques minutes)
    for _ in range(300):
        r = c.get(f"{BASE}/search/semantic", params={"q": "coucher de soleil sur la mer"}, timeout=300)
        if r.status_code == 503:  # worker encore en train de télécharger/charger CLIP
            continue
        res = r.raise_for_status().json()
        if any(i["id"] == media_id for i in res["items"]):
            break
        time.sleep(2)
    else:
        sys.exit("échec : pas d'embedding CLIP (voir docker compose logs worker-ml)")
    print("recherche ok", [round(i["score"], 3) for i in res["items"][:3]])

    # Détails, lieux, albums, corbeille
    d = c.get(f"{BASE}/media/{media_id}").raise_for_status().json()
    assert d["city"] and d["camera_model"] == "E2E" and isinstance(d["tags"], list), d
    places = c.get(f"{BASE}/places").json()["places"]
    assert any(p["city"] == d["city"] for p in places), places
    by_place = c.get(f"{BASE}/media/query", params={"city": d["city"], "country_code": d["country_code"]}).json()
    assert any(i["id"] == media_id for i in by_place["items"])
    album = c.post(f"{BASE}/albums", json={"title": "E2E", "media_ids": [media_id]}).raise_for_status().json()
    assert album["added"] == 1
    assert c.post(f"{BASE}/albums/{album['id']}/media", json={"ids": [media_id]}).json() == {"added": 0}
    listed = next(a for a in c.get(f"{BASE}/albums").json()["albums"] if a["id"] == album["id"])
    assert (listed["media_count"], listed["cover_id"]) == (1, media_id), listed
    assert c.get(f"{BASE}/media/query", params={"album_id": album["id"]}).json()["items"][0]["id"] == media_id
    assert c.delete(f"{BASE}/albums/{album['id']}").status_code == 204
    assert c.get(f"{BASE}/people").status_code == 200 and c.get(f"{BASE}/memories").status_code == 200
    assert c.post(f"{BASE}/media/trash", json={"ids": [media_id]}).json() == {"trashed": 1}
    assert c.get(f"{BASE}/media/{media_id}").status_code == 404
    assert not any(i["id"] == media_id for i in c.get(f"{BASE}/media/timeline").json()["items"])
    print("albums, lieux, corbeille ok")
    print("E2E OK")


if __name__ == "__main__":
    main(*sys.argv[1:3])
