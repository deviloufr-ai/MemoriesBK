"""Regroupement complet des visages, pour réparer les personnes mélangées ou éclatées par le rattachement en ligne.

    docker compose exec worker python regroup_faces.py            # simulation : chiffres seulement
    docker compose exec worker python regroup_faces.py --apply    # écrit, en un lot annulable (face_events)

Ne bouge jamais un visage corrigé à la main (manual). Les personnes nommées gardent leur id et leur nom : leur
« noyau » (visages corrigés + visages proches de leur centre) sert de référence, et chaque autre visage va à la
personne nommée qui a le plus de voisins parmi ses k plus proches noyaux. Le reste est regroupé par centres (pas
de chaînage) ; un groupe reprend l'id de la personne sans nom qui en avait le plus de visages. Visages arrivés
pendant le calcul : laissés tels quels.
"""
import sys
import uuid
from collections import Counter

import numpy as np

import tasks

CORE_SIM = 0.5       # un visage est dans le noyau d'une personne nommée s'il ressemble autant à son centre
CLUSTER_SIM = 0.5    # même seuil pour regrouper les visages sans personne nommée
TOP_M, ASSIGN_SIM, MARGIN = tasks.FACE_TOP_M, tasks.FACE_ASSIGN_SIM, tasks.FACE_MARGIN  # même règle qu'en ligne
CHUNK = 2048


def cores(E, person, manual, named):
    """Noyau de chaque personne nommée : parti du visage qui a le plus de voisins (pas de la moyenne de tous, qui
    tombe entre deux personnes quand l'une a avalé l'autre ; ni des seuls visages corrigés, souvent 1 à 3 cas
    difficiles), puis élargi aux visages proches du centre. Visages manuels toujours dedans."""
    core_idx, core_pid = [], []
    rng = np.random.default_rng(0)
    for p in named:
        idx = np.where(person == p)[0]
        if not len(idx):
            continue
        man = idx[manual[idx]]
        s = rng.choice(idx, min(len(idx), 3000), replace=False)
        S = E[s] @ E[s].T
        keep = s[S[(S >= CORE_SIM).sum(1).argmax()] >= CORE_SIM]
        for _ in range(3):
            c = E[keep].mean(0)
            c /= np.linalg.norm(c)
            near = idx[E[idx] @ c >= CORE_SIM]
            keep = np.union1d(near, man) if len(near) or len(man) else keep
        core_idx += list(keep)
        core_pid += [p] * len(keep)
    return np.asarray(core_idx, dtype=int), np.asarray(core_pid, dtype=object)


def classify(E, rows, core_idx, core_pid):
    """Pour chaque visage de `rows` : la personne nommée dont les TOP_M visages de noyau les plus proches lui
    ressemblent le plus en moyenne (sans compter le visage lui-même). Comparer à chaque personne séparément évite
    qu'une personne très photographiée gagne par le nombre. None si sous ASSIGN_SIM ou trop proche d'une 2e."""
    pids = sorted(set(core_pid), key=str)
    cols = [np.where(core_pid == p)[0] for p in pids]
    best, score = [None] * len(rows), np.zeros(len(rows))
    if not pids:
        return best, score
    for s in range(0, len(rows), CHUNK):
        r = rows[s:s + CHUNK]
        S = E[r] @ E[core_idx].T
        S[core_idx[None, :] == r[:, None]] = -1  # pas de vote pour soi-même
        per = np.empty((len(r), len(pids)), dtype=np.float32)
        for j, c in enumerate(cols):
            m = min(TOP_M, len(c))
            per[:, j] = -np.partition(-S[:, c], m - 1, axis=1)[:, :m].mean(1) if m else -1
        order = np.argsort(-per, axis=1)
        top1 = per[np.arange(len(r)), order[:, 0]]
        top2 = per[np.arange(len(r)), order[:, 1]] if len(pids) > 1 else np.full(len(r), -1.0)
        for i in range(len(r)):
            score[s + i] = top1[i]
            if top1[i] >= ASSIGN_SIM and top1[i] - top2[i] >= MARGIN:
                best[s + i] = pids[order[i, 0]]
    return best, score


def regroup(E, person, manual, named, det_score):
    """E : visages normalisés (n×d). person : id actuel ou None. manual : corrigé à la main. named : ids des
    personnes nommées. Renvoie la nouvelle personne de chaque visage : un id de `named`, un entier (groupe sans
    nom), ou None/l'id actuel pour un visage manuel (inchangé)."""
    n = len(E)
    person, manual = np.asarray(person, dtype=object), np.asarray(manual, dtype=bool)
    core_idx, core_pid = cores(E, person, manual, named)
    out = np.empty(n, dtype=object)
    out[manual] = person[manual]
    best, _ = classify(E, np.where(~manual)[0], core_idx, core_pid)
    rest = []
    named_set = set(named)
    for r, p in zip(np.where(~manual)[0], best):
        if person[r] in named_set and p is not None and p != person[r]:
            out[r] = person[r]  # d'une personne nommée à une autre (frères et sœurs…) : on ne tranche pas, « À vérifier » le fera
        elif p is None:
            rest.append(r)
        else:
            out[r] = p

    # Le reste : regroupement par centres, des visages les plus nets aux plus flous, puis une passe de réaffectation
    rest = np.asarray(sorted(rest, key=lambda i: -det_score[i]), dtype=int)
    cents, label = [], np.full(n, -1)
    for i in rest:
        if cents:
            sims = np.asarray(cents) @ E[i]
            j = int(sims.argmax())
            if sims[j] >= CLUSTER_SIM:
                label[i] = j
                m = (label == j).sum()
                c = cents[j] * (m - 1) + E[i]
                cents[j] = c / np.linalg.norm(c)
                continue
        label[i] = len(cents)
        cents.append(E[i].copy())
    if cents:
        C = np.asarray(cents)
        for s in range(0, len(rest), CHUNK):
            rows = rest[s:s + CHUNK]
            sims = E[rows] @ C.T
            best = sims.argmax(1)
            ok = sims[np.arange(len(rows)), best] >= CLUSTER_SIM
            label[rows[ok]] = best[ok]
    for i in rest:
        out[i] = int(label[i])
    return out


def _load(conn, owners):
    rows = conn.execute(
        "SELECT f.id, f.owner_id, f.person_id, f.manual, f.det_score, f.embedding, p.name, p.hidden "
        "FROM faces f LEFT JOIN people p ON p.id = f.person_id WHERE f.owner_id = ANY(%s)", (owners,)).fetchall()
    if not rows:
        return rows, None, set()
    E =np.asarray([r["embedding"] for r in rows], dtype=np.float32)
    E /= np.linalg.norm(E, axis=1, keepdims=True)
    # Ancres : personnes nommées, cachées (ex. fusionnées) ou partagées : leur id doit rester stable
    named = {r["person_id"] for r in rows if r["person_id"] and (r["name"] or r["hidden"])}
    named |= {r["person_id"] for r in conn.execute(
        "SELECT DISTINCT person_id FROM family_share_people s JOIN people p ON p.id = s.person_id "
        "WHERE p.owner_id = ANY(%s)", (owners,)).fetchall()}
    return rows, E, named


def run(conn, owners, apply=False) -> dict:
    rows, E, named = _load(conn, owners)
    if not rows:
        return {}
    out = regroup(E, [r["person_id"] for r in rows], [r["manual"] for r in rows], sorted(named, key=str),
                  [r["det_score"] or 0 for r in rows])
    # Groupes sans nom : reprendre l'id sans nom le plus représenté (une seule fois), sinon nouvelle personne
    by_label = {}
    for r, o in zip(rows, out):
        if isinstance(o, int):
            by_label.setdefault(o, []).append(r)
    taken, new_people, target = set(named), [], {}
    for lab, rs in sorted(by_label.items(), key=lambda kv: -len(kv[1])):
        votes = Counter(r["person_id"] for r in rs if r["person_id"] and r["person_id"] not in taken)
        pid = votes.most_common(1)[0][0] if votes else None
        if pid is None:
            pid = uuid.uuid4()
            new_people.append((pid, Counter(r["owner_id"] for r in rs).most_common(1)[0][0]))
        taken.add(pid)
        target[lab] = pid
    moves = []
    for r, o in zip(rows, out):
        to = target[o] if isinstance(o, int) else o
        if not r["manual"] and to != r["person_id"]:
            moves.append((r["id"], r["person_id"], to))

    named_name = {r["person_id"]: r["name"] for r in rows if r["name"]}
    stats = {"faces": len(rows), "moves": len(moves), "new_people": len(new_people),
             "groups": len(set(target.values()) | {o for o in out if o is not None and not isinstance(o, int)})}
    before = Counter(named_name.get(r["person_id"]) for r in rows if r["person_id"] in named_name)
    after = Counter(named_name.get(target[o] if isinstance(o, int) else o) for o in out)
    stats["named"] = {n: (before.get(n, 0), after.get(n, 0)) for n in sorted(set(named_name.values()))}
    stats["left_named"] = Counter(named_name[f] for _, f, t in moves if f in named_name and t not in named_name)
    stats["top_moves"] = Counter((named_name.get(f, "?"), named_name.get(t, "?")) for _, f, t in moves).most_common(12)
    if not apply or not moves:
        return stats

    batch = uuid.uuid4()
    with conn.transaction():
        conn.execute("LOCK TABLE faces IN SHARE ROW EXCLUSIVE MODE")  # pas de detect_faces pendant l'écriture
        with conn.cursor().copy("COPY people (id, owner_id) FROM STDIN") as cp:
            for pid, owner in new_people:
                cp.write_row((pid, owner))
        conn.execute("CREATE TEMP TABLE regroup (face_id uuid, from_person uuid, to_person uuid) ON COMMIT DROP")
        with conn.cursor().copy("COPY regroup FROM STDIN") as cp:
            for m in moves:
                cp.write_row(m)
        # Lot annulable depuis l'app (POST /face-events/{batch}/undo), au nom du propriétaire de chaque visage
        conn.execute("INSERT INTO face_events (batch, kind, face_id, from_person, to_person, from_manual, user_id) "
                     "SELECT %s, 'move', f.id, f.person_id, g.to_person, f.manual, f.owner_id "
                     "FROM regroup g JOIN faces f ON f.id = g.face_id AND f.person_id IS NOT DISTINCT FROM g.from_person "
                     "AND NOT f.manual", (batch,))
        conn.execute("UPDATE faces f SET person_id = g.to_person FROM regroup g WHERE f.id = g.face_id "
                     "AND f.person_id IS NOT DISTINCT FROM g.from_person AND NOT f.manual")
        # Personnes sans nom devenues vides et que rien ne référence plus
        stats["deleted_people"] = conn.execute(
            "DELETE FROM people p WHERE p.owner_id = ANY(%s) AND p.name IS NULL AND NOT p.hidden "
            "AND p.merged_into IS NULL AND NOT EXISTS (SELECT 1 FROM faces f WHERE f.person_id = p.id) "
            "AND NOT EXISTS (SELECT 1 FROM people q WHERE q.merged_into = p.id) "
            "AND NOT EXISTS (SELECT 1 FROM family_share_people s WHERE s.person_id = p.id) "
            "AND NOT EXISTS (SELECT 1 FROM face_events e WHERE p.id IN (e.from_person, e.to_person))",
            (owners,)).rowcount
    stats["batch"] = str(batch)
    return stats


def main(apply: bool):
    with tasks.db() as conn:
        families = conn.execute("SELECT array_agg(id) AS ids FROM users GROUP BY coalesce(family_id, id)").fetchall()
        for f in families:
            stats = run(conn, f["ids"], apply)
            if not stats:
                continue
            print(f"famille de {len(f['ids'])} compte(s) : {stats['faces']} visages, {stats['moves']} déplacés, "
                  f"{stats['groups']} personnes, {stats['new_people']} nouvelles"
                  + (f", {stats['deleted_people']} fiches vides supprimées, lot {stats['batch']}" if "batch" in stats else ""))
            for name, (b, a) in stats["named"].items():
                print(f"  {name:<20} {b:>6} -> {a:<6} (en perd {stats['left_named'].get(name, 0)})")
            print("  mouvements les plus fréquents (? = sans nom) :",
                  ", ".join(f"{a}→{b} {n}" for (a, b), n in stats["top_moves"]))
    if not apply:
        print("Simulation : rien n'est écrit. Relancer avec --apply.")


if __name__ == "__main__":
    main("--apply" in sys.argv)
