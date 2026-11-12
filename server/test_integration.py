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
        assert api.family_remove(api.Ids(ids=[m["manual"]]), f["f"]["b"])["removed"] == 0  # pas celui qui a partagé
    finally:
        assert api.family_remove(api.Ids(ids=[m["manual"]]), f["f"]["a"])["removed"] == 1


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


def test_health():
    h = api.health()
    assert h["db"] and h["redis"]
