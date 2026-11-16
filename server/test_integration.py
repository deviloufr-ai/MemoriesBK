"""Tests d'intégration contre la vraie base (dans le conteneur api) : visibilité Famille, partages, nettoyage.

    docker compose run --rm --no-deps -T api python -m pytest -q test_integration.py

Crée une famille jetable (pytest-…@test.local) et la supprime à la fin ; ne touche à aucune donnée existante.
"""
import os
import uuid
from datetime import datetime, timezone

import numpy as np
import pytest

if not os.environ.get("DATABASE_URL"):
    pytest.skip("pas de base (lancer dans le conteneur api)", allow_module_level=True)

import api  # noqa: E402
from fastapi import HTTPException  # noqa: E402


def _dt(year):
    return datetime(year, 6, 1, tzinfo=timezone.utc)


@pytest.fixture(scope="module")
def fam():
    tag = uuid.uuid4().hex[:8]
    fid, ofid = uuid.uuid4(), uuid.uuid4()
    with api.pool.connection() as c:
        def user(name, family, admin=False, restricted=False):
            return c.execute("INSERT INTO users (email, password_hash, family_id, is_admin, restricted) "
                             "VALUES (%s, 'x', %s, %s, %s) RETURNING id",
                             (f"pytest-{tag}-{name}@test.local", family, admin, restricted)).fetchone()["id"]

        u = {"a": user("a", fid, admin=True), "b": user("b", fid), "r": user("r", fid, restricted=True), "o": user("o", ofid)}

        def media(owner, year, **cols):
            return c.execute("INSERT INTO media (owner_id, sha256, kind, mime_type, original_name, size_bytes, status, "
                             "taken_at, sharpness) VALUES (%s, %s, 'image', 'image/jpeg', 'x.jpg', 1, 'ready', %s, %s) "
                             "RETURNING id", (owner, os.urandom(32), _dt(year), cols.get("sharpness", 500))).fetchone()["id"]

        m = {"manual": media(u["a"], 2021), "face_old": media(u["a"], 2019), "face_new": media(u["a"], 2022),
             "pet": media(u["a"], 2020), "blur": media(u["a"], 2021, sharpness=1), "b_own": media(u["b"], 2021)}
        person = c.execute("INSERT INTO people (owner_id, name) VALUES (%s, 'Testkid') RETURNING id", (u["a"],)).fetchone()["id"]
        for key in ("face_old", "face_new"):
            c.execute("INSERT INTO faces (media_id, owner_id, person_id, bbox, det_score, embedding) "
                      "VALUES (%s, %s, %s, '{0,0,1,1}', 0.9, %s)", (m[key], u["a"], person, np.random.rand(512).astype(np.float32)))
        pet = c.execute("INSERT INTO pets (owner_id, name, examples) VALUES (%s, 'Testdog', %s) RETURNING id",
                        (u["a"], [m["pet"]])).fetchone()["id"]
        c.execute("INSERT INTO pet_media (pet_id, media_id) VALUES (%s, %s)", (pet, m["pet"]))
    families = {k: api.current_family(v) for k, v in u.items()}
    yield {"u": u, "m": m, "f": families, "person": person, "pet": pet}
    with api.pool.connection() as c:
        c.execute("DELETE FROM users WHERE email LIKE %s", (f"pytest-{tag}-%",))


def visible(f, who):
    return {i["id"] for i in api.family_media(f["f"][who], 30000)["items"]}


def test_photos_tab_shows_only_my_photos(fam):
    ids = {i["id"] for i in api.timeline(fam["f"]["b"], None, 500)["items"]}
    assert ids == {fam["m"]["b_own"]}


def _family_reset(media_ids):
    with api.pool.connection() as c:
        c.execute("DELETE FROM family_shared WHERE media_id = ANY(%s)", (media_ids,))
        c.execute("DELETE FROM family_hidden WHERE media_id = ANY(%s)", (media_ids,))


def test_manual_share_visibility_and_removal(fam):
    f, m = fam, fam["m"]
    assert api.family_add(api.Ids(ids=[m["manual"]]), f["f"]["a"])["added"] == 1
    try:
        for who in ("a", "b", "r"):
            assert m["manual"] in visible(f, who)
        assert m["manual"] not in visible(f, "o")
        api._owned(m["manual"], f["f"]["r"], shared=True)  # le membre limité peut ouvrir la photo
        with pytest.raises(HTTPException):
            api._owned(m["manual"], f["f"]["o"], shared=True)
        with pytest.raises(HTTPException):  # le membre limité ne retire rien pour les autres
            api.family_remove(api.Ids(ids=[m["manual"]]), f["f"]["r"])
        # Tout membre peut retirer, pour toute la famille, une photo qu'il n'a pas partagée
        assert api.family_remove(api.Ids(ids=[m["manual"]]), f["f"]["b"])["removed"] == 1
        for who in ("a", "b", "r"):
            assert m["manual"] not in visible(f, who)
        with pytest.raises(HTTPException):
            api._owned(m["manual"], f["f"]["r"], shared=True)
        assert m["manual"] in {i["id"] for i in api.family_hidden_list(f["f"]["a"])["items"]}
        assert api.family_unhide(api.Ids(ids=[m["manual"]]), f["f"]["a"])["unhidden"] == 1
        assert m["manual"] in visible(f, "r")
        assert not api.family_hidden_list(f["f"]["a"])["items"]
    finally:
        _family_reset([m["manual"]])


def test_remove_photo_shared_by_person_rule(fam):
    """Photo arrivée par une personne partagée : retirable seule, la règle continue pour les autres photos."""
    f, m = fam, fam["m"]
    api.family_people_add(api.PersonReq(person_id=f["person"]), f["f"]["a"])
    try:
        assert api.family_remove(api.Ids(ids=[m["face_old"]]), f["f"]["b"])["removed"] == 1
        for who in ("a", "b", "r"):
            seen = visible(f, who)
            assert m["face_old"] not in seen and m["face_new"] in seen
        assert [i["id"] for i in api.family_hidden_list(f["f"]["b"])["items"]] == [m["face_old"]]
        # Partager à nouveau explicitement annule le retrait
        api.family_add(api.Ids(ids=[m["face_old"]]), f["f"]["a"])
        assert m["face_old"] in visible(f, "b")
        assert not api.family_hidden_list(f["f"]["b"])["items"]
    finally:
        api.family_people_remove(f["person"], f["f"]["a"])
        _family_reset([m["face_old"], m["face_new"]])


def test_person_rule_with_from_year(fam):
    f, m = fam, fam["m"]
    api.family_people_add(api.PersonReq(person_id=f["person"]), f["f"]["a"])
    try:
        assert {m["face_old"], m["face_new"]} <= visible(f, "b")
        api.family_people_add(api.PersonReq(person_id=f["person"], from_year=2020), f["f"]["a"])
        seen = visible(f, "b")
        assert m["face_new"] in seen and m["face_old"] not in seen
        assert api.family_people(f["f"]["b"])["from_years"][str(f["person"])] == 2020
    finally:
        api.family_people_remove(f["person"], f["f"]["a"])
    assert m["face_new"] not in visible(f, "b")


def test_pet_rule(fam):
    f, m = fam, fam["m"]
    api.family_pets_add(api.PetIdReq(pet_id=f["pet"]), f["f"]["a"])
    try:
        assert m["pet"] in visible(f, "r")
        assert api.family_pets_remove(f["pet"], f["f"]["b"])["removed"] == 0
    finally:
        assert api.family_pets_remove(f["pet"], f["f"]["a"])["removed"] == 1


def test_cleanup_keep_hides_photo(fam):
    f, m = fam, fam["m"]
    assert m["blur"] in {i["id"] for i in api.cleanup("blurry", f["f"]["a"], None)["items"]}
    api.cleanup_keep("blurry", api.Ids(ids=[m["blur"]]), f["f"]["a"])
    assert m["blur"] not in {i["id"] for i in api.cleanup("blurry", f["f"]["a"], None)["items"]}


def test_batch_hide_and_unhide(fam):
    f, m = fam, fam["m"]
    ids = [m["manual"], m["face_old"]]
    timeline = lambda: {i["id"] for i in api.timeline(f["f"]["a"], None, 500)["items"]}
    assert set(ids) <= timeline()
    assert api.hide_media(api.Ids(ids=ids), f["f"]["a"])["hidden"] == 2
    try:
        assert not set(ids) & timeline()
        assert set(ids) <= {i["id"] for i in api.list_hidden(f["f"]["a"])["items"]}
        # toujours dans la page de la personne
        assert m["face_old"] in {i["id"] for i in api.media_query(f["f"]["a"], person_id=f["person"], limit=100)["items"]}
        assert api.hide_media(api.Ids(ids=[m["b_own"]]), f["f"]["o"])["hidden"] == 0  # autre famille
    finally:
        assert api.unhide_media(api.Ids(ids=ids), f["f"]["a"])["unhidden"] == 2
    assert set(ids) <= timeline()


def test_merge_requires_confirmation_and_undo(fam):
    f = fam
    with api.pool.connection() as c:
        other = c.execute("INSERT INTO people (owner_id) VALUES (%s) RETURNING id", (f["u"]["a"],)).fetchone()["id"]
        c.execute("INSERT INTO faces (media_id, owner_id, person_id, bbox, det_score, embedding) "
                  "VALUES (%s, %s, %s, '{0,0,1,1}', 0.9, %s)", (f["m"]["manual"], f["u"]["a"], other, np.random.rand(512).astype(np.float32)))
    with pytest.raises(HTTPException) as e:  # même nom : pas de fusion silencieuse
        api.patch_person(other, api.PersonPatch(name="testkid"), f["f"]["a"])
    assert e.value.status_code == 409
    r = api.patch_person(other, api.PersonPatch(name="testkid", merge=True), f["f"]["a"])
    assert r["merged"] and r["id"] == str(f["person"])
    names = {p["id"] for p in api.list_people(f["f"]["a"])["people"]}
    assert other not in names
    api.undo_face_batch(uuid.UUID(r["batch"]), f["f"]["a"])
    with api.pool.connection() as c:
        assert c.execute("SELECT count(*) AS n FROM faces WHERE person_id = %s", (other,)).fetchone()["n"] == 1
        assert not c.execute("SELECT hidden FROM people WHERE id = %s", (other,)).fetchone()["hidden"]


def test_bulk_move_faces_and_undo(fam):
    f = fam
    r = api.move_person_faces(f["person"], api.FacesMove(media_ids=[f["m"]["face_old"]], name="Testsibling"), f["f"]["a"])
    assert r["moved"] == 1
    kid = {i["id"] for i in api.media_query(f["f"]["a"], person_id=f["person"], limit=100)["items"]}
    assert f["m"]["face_old"] not in kid
    api.undo_face_batch(uuid.UUID(r["batch"]), f["f"]["a"])
    kid = {i["id"] for i in api.media_query(f["f"]["a"], person_id=f["person"], limit=100)["items"]}
    assert f["m"]["face_old"] in kid


def test_review_face_answer(fam):
    f = fam
    with api.pool.connection() as c:
        face = c.execute("SELECT id FROM faces WHERE media_id = %s", (f["m"]["face_new"],)).fetchone()["id"]
    r = api.review_answer(api.ReviewAnswer(kind="face", subject_id=f["person"], media_id=f["m"]["face_new"],
                                           face_id=face, yes=False), f["f"]["a"])
    with api.pool.connection() as c:
        assert c.execute("SELECT person_id FROM faces WHERE id = %s", (face,)).fetchone()["person_id"] is None
    api.undo_face_batch(uuid.UUID(r["batch"]), f["f"]["a"])
    assert api.review_queue(f["f"]["a"], 20) is not None


def test_health():
    h = api.health()
    assert h["db"] and h["redis"]


def test_publish_apk(monkeypatch, tmp_path):
    from fastapi.testclient import TestClient
    monkeypatch.setattr(api, "APK_DIR", tmp_path)
    monkeypatch.setattr(api, "APK_PUBLISH_TOKEN", "secret-test")
    api._redis.delete("apk-publish")
    client = TestClient(api.app)
    apk = b"PK\x03\x04" + os.urandom(4096)
    put = lambda v, body=apk, tok="secret-test": client.put(f"/api/v1/app/apk?version={v}", content=body,
                                                            headers={"X-Publish-Token": tok})
    assert put(5, tok="faux").status_code == 403
    assert put(5, body=b"pas un apk" * 200).status_code == 400
    assert put(5).status_code == 200
    assert put(4).status_code == 409  # jamais de retour en arrière
    assert client.get("/MemoriesBK.version").text == "5"
    assert client.get("/MemoriesBK.apk").content == apk
    assert not list(tmp_path.glob(".upload-*"))
    api._redis.delete("apk-publish")


def _unit(n, seed):
    v = np.random.default_rng(seed).standard_normal(n).astype(np.float32)
    return v / np.linalg.norm(v)


@pytest.fixture
def search_media(fam):
    """3 photos : 0 a une description, 0 et 1 ont un vecteur du gros modèle, 2 seulement celui de base (arrivée récente)."""
    a = fam["u"]["a"]
    with api.pool.connection() as c:
        ids = [c.execute("INSERT INTO media (owner_id, sha256, kind, mime_type, original_name, size_bytes, status, "
                         "taken_at) VALUES (%s, %s, 'image', 'image/jpeg', 'x.jpg', 1, 'ready', %s) RETURNING id",
                         (a, os.urandom(32), _dt(2023))).fetchone()["id"] for _ in range(3)]
        c.execute("UPDATE media SET description = 'panneau de la boulangerie Martin', ocr_text = 'Fermé le lundi' "
                  "WHERE id = %s", (ids[0],))
        for i, mid in enumerate(ids):
            c.execute("INSERT INTO embeddings (media_id, model, embedding) VALUES (%s, %s, %s)",
                      (mid, api.CLIP_MODEL_ID, _unit(512, i)))
            if i < 2:
                c.execute("INSERT INTO search_embeddings (media_id, model, embedding) VALUES (%s, 'test/big', %s)",
                          (mid, _unit(1024, 10 + i)))
    yield ids
    with api.pool.connection() as c:
        c.execute("DELETE FROM media WHERE id = ANY(%s)", (ids,))
    api._redis.delete(api.SEARCH_READY_KEY)


def _fake_queries(monkeypatch, base, big):
    class Job:
        def __init__(self, v):
            self.v = v

        def get(self, timeout):
            return self.v.tolist()

    calls = []

    def apply_async(args):
        calls.append(len(args) > 1 and args[1])
        return Job(big if len(args) > 1 and args[1] else base)

    monkeypatch.setattr(api.embed_query, "apply_async", apply_async)
    return calls


def test_search_words_first_then_base_model(fam, search_media, monkeypatch):
    ids = search_media
    calls = _fake_queries(monkeypatch, _unit(512, 1), _unit(1024, 11))
    monkeypatch.setattr(api, "SEARCH_MODEL_ID", None)
    items = [i["id"] for i in api.search_semantic(fam["f"]["a"], "boulangerie lundi", 60)["items"]]
    assert items[0] == ids[0]          # mots de la description + du texte lu
    assert items[1] == ids[1]          # puis CLIP : vecteur identique à la requête
    assert set(ids) <= set(items) and len(items) == len(set(items))
    assert calls == [False]
    # Famille restreinte sans règle : ne voit pas ces photos, même par les mots
    assert not set(ids) & {i["id"] for i in api.search_semantic(fam["f"]["r"], "boulangerie", 60)["items"]}


def test_search_big_model_only_when_ready(fam, search_media, monkeypatch):
    ids = search_media
    calls = _fake_queries(monkeypatch, _unit(512, 2), _unit(1024, 11))
    monkeypatch.setattr(api, "SEARCH_MODEL_ID", "test/big")
    api._redis.delete(api.SEARCH_READY_KEY)
    items = [i["id"] for i in api.search_semantic(fam["f"]["a"], "photo", 60)["items"]]
    assert calls == [False] and items[0] == ids[2]   # pas encore prêt : modèle de base (requête = vecteur de 2)
    api._redis.set(api.SEARCH_READY_KEY, "test/big")
    calls.clear()
    items = [i["id"] for i in api.search_semantic(fam["f"]["a"], "photo", 60)["items"]]
    assert calls == [False, True]
    assert items[0] == ids[1]          # gros modèle : vecteur identique à la requête
    assert items[-1] == ids[2]         # sans vecteur du gros modèle : modèle de base, en fin de liste
    assert len(items) == 3


def test_search_backfill_marks_ready(fam, search_media):
    import tasks

    ids = search_media
    with api.pool.connection() as c:
        todo = tasks._search_backfill(c, api.CLIP_MODEL_ID, "test/big")
        assert [r["id"] for r in todo] == [ids[2]]
        assert api._redis.get(tasks.SEARCH_READY_KEY) is None   # 1 sur 3 manquant : pas prêt
        c.execute("INSERT INTO search_embeddings (media_id, model, embedding) VALUES (%s, 'test/big', %s)",
                  (ids[2], _unit(1024, 12)))
        assert tasks._search_backfill(c, api.CLIP_MODEL_ID, "test/big") == []
        assert api._redis.get(tasks.SEARCH_READY_KEY) == b"test/big"


def test_detect_faces_is_additive(fam, monkeypatch):
    """Ré-analyse en meilleure définition : visage déjà en base = même id, même personne, correction gardée ;
    visage non retrouvé gardé ; nouveaux visages rattachés au plus proche ou à une nouvelle personne."""
    import tasks

    a, kid = fam["u"]["a"], fam["person"]
    with api.pool.connection() as c:
        mid = c.execute("INSERT INTO media (owner_id, sha256, kind, mime_type, original_name, size_bytes, status, "
                        "taken_at, storage_path) VALUES (%s, %s, 'image', 'image/jpeg', 'x.jpg', 1, 'ready', %s, "
                        "'2023/x.jpg') RETURNING id", (a, os.urandom(32), _dt(2023))).fetchone()["id"]
        other = c.execute("INSERT INTO people (owner_id) VALUES (%s) RETURNING id", (a,)).fetchone()["id"]
        manual = c.execute("INSERT INTO faces (media_id, owner_id, person_id, bbox, det_score, embedding, manual) "
                           "VALUES (%s, %s, %s, '{0.1,0.1,0.2,0.2}', 0.7, %s, true) RETURNING id",
                           (mid, a, other, _unit(512, 50))).fetchone()["id"]
        missed = c.execute("INSERT INTO faces (media_id, owner_id, person_id, bbox, det_score, embedding) "
                           "VALUES (%s, %s, %s, '{0.6,0.6,0.7,0.7}', 0.7, %s) RETURNING id",
                           (mid, a, other, _unit(512, 51))).fetchone()["id"]
        kid_emb = np.asarray(c.execute("SELECT embedding FROM faces WHERE media_id = %s",
                                       (fam["m"]["face_old"],)).fetchone()["embedding"], dtype=np.float32)
    sharper = _unit(512, 52)
    found = [([0.11, 0.1, 0.21, 0.2], 0.9, sharper),                    # le visage corrigé, mieux défini
             ([0.4, 0.4, 0.45, 0.45], 0.8, kid_emb / np.linalg.norm(kid_emb)),  # petit visage, c'est Testkid
             ([0.8, 0.1, 0.85, 0.15], 0.8, _unit(512, 53))]              # inconnu
    seen = {}
    monkeypatch.setattr(tasks, "FACE_SOURCE_PX", 4096)
    monkeypatch.setattr("ml.detect_faces", lambda path, max_px=0: seen.update(path=str(path), px=max_px) or found)
    tasks.detect_faces(str(mid))
    assert seen == {"path": str(tasks.PHOTOS_DIR / "2023/x.jpg"), "px": 4096}
    with api.pool.connection() as c:
        faces = {r["id"]: r for r in c.execute("SELECT id, person_id, manual, embedding FROM faces WHERE media_id = %s",
                                                (mid,)).fetchall()}
        scan = c.execute("SELECT faces_scan_px FROM media WHERE id = %s", (mid,)).fetchone()["faces_scan_px"]
        c.execute("DELETE FROM media WHERE id = %s", (mid,))
        c.execute("DELETE FROM people WHERE id = %s OR (owner_id = %s AND id NOT IN (SELECT person_id FROM faces "
                  "WHERE person_id IS NOT NULL) AND name IS NULL)", (other, a))
    assert len(faces) == 4 and scan == 4096
    assert faces[manual]["manual"] and faces[manual]["person_id"] == other
    assert np.allclose(np.asarray(faces[manual]["embedding"]), sharper, atol=1e-5)
    assert faces[missed]["person_id"] == other            # non retrouvé : gardé tel quel
    new = [f for i, f in faces.items() if i not in (manual, missed)]
    assert sorted(f["person_id"] == kid for f in new) == [False, True]
    assert all(f["person_id"] not in (None, other) for f in new)
