"""Rattachement et regroupement des visages, sans base : python -m pytest -q test_faces.py"""
import numpy as np

import regroup_faces as R
from tasks import pick_person


def _people(rng, n_people, per, spread=0.35, d=64):
    """Visages synthétiques : n_people centres, `per` visages bruités autour de chacun."""
    C = rng.normal(size=(n_people, d))
    E = np.repeat(C, per, axis=0) + spread * rng.normal(size=(n_people * per, d))
    return E / np.linalg.norm(E, axis=1, keepdims=True), np.repeat(np.arange(n_people), per)


def test_pick_person_does_not_follow_a_single_lookalike():
    # Un seul visage très proche chez B ne bat pas cinq visages assez proches chez A
    near = [("A", 0.6)] * 5 + [("B", 0.9)] + [("B", 0.2)] * 4
    assert pick_person(near) == "A"
    assert pick_person([("A", 0.3)] * 5) is None                 # trop peu ressemblant : nouvelle personne
    assert pick_person([("A", 0.6)] * 5 + [("B", 0.59)] * 5) is None  # indécis
    assert pick_person([]) is None


def test_regroup_fixes_chained_people_and_keeps_manual():
    rng = np.random.default_rng(0)
    E, _ = _people(rng, 4, 30)
    E = np.delete(E, range(80, 90), axis=0)  # le groupe avalé (20) est plus petit que « kid » (30)
    named = ["kid", "dad"]
    # État cassé : la personne 2 (sans nom) a été avalée par « kid », la 3 éclatée en singletons
    person = np.array(["kid"] * 30 + ["dad"] * 30 + ["kid"] * 20 + [f"u{i}" for i in range(30)], dtype=object)
    manual = np.zeros(len(E), bool)
    manual[31] = True
    person[31] = None  # « ce n'est pas papa » : corrigé à la main, ne doit pas bouger
    out = R.regroup(E, person, manual, named, np.ones(len(E)))
    assert list(out[:30]) == ["kid"] * 30 and out[31] is None
    assert all(o == "dad" for i, o in enumerate(out[30:60]) if i != 1)
    # Les intrus sortent de « kid » et forment un seul groupe sans nom ; les singletons se rassemblent
    intruders, split = out[60:80], out[80:110]
    assert all(isinstance(o, int) for o in intruders) and len(set(intruders)) == 1
    assert all(isinstance(o, int) for o in split) and len(set(split)) == 1
    assert intruders[0] != split[0]


def test_regroup_never_moves_between_named_people():
    rng = np.random.default_rng(1)
    E, _ = _people(rng, 2, 20)
    person = np.array(["a"] * 20 + ["a"] * 5 + ["b"] * 15, dtype=object)  # 5 visages de b rangés chez a
    out = R.regroup(E, person, np.zeros(len(E), bool), ["a", "b"], np.ones(len(E)))
    assert list(out[20:25]) == ["a"] * 5 and list(out[25:]) == ["b"] * 15
