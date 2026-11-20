from datetime import datetime, timedelta

import numpy as np

import memvideo


def test_weekly_groups_by_iso_week_best_first():
    d = datetime(2026, 1, 5)  # lundi
    rows = [{"taken_at": d, "s": 1}, {"taken_at": d + timedelta(days=6), "s": 3}, {"taken_at": d + timedelta(days=7), "s": 2}]
    weeks = memvideo.weekly(rows, lambda r: r["s"])
    assert [[r["s"] for r in w] for w in weeks] == [[3, 1], [2]]


def test_align_puts_landmarks_on_template_and_rejects_profiles():
    img = np.zeros((1000, 1000, 3), np.uint8)
    # visage de face deux fois plus grand que le gabarit, décalé et tourné de 90° : doit revenir pile sur TL_DST
    src = (memvideo.TL_DST - 360) * 0.5
    src = src @ np.array([[0, 1], [-1, 0]]) + [400, 300]
    img[tuple(np.round(src[0][::-1]).astype(int))] = 255  # œil gauche marqué
    out = memvideo.align(img, src)
    assert out.shape == (memvideo.TL_S, memvideo.TL_S, 3)
    y, x = np.unravel_index(out[:, :, 0].argmax(), out.shape[:2])
    assert abs(x - memvideo.TL_DST[0][0]) <= 2 and abs(y - memvideo.TL_DST[0][1]) <= 2
    profile = src.copy()
    profile[2] = profile[0]  # nez au niveau d'un œil
    assert memvideo.align(img, profile) is None
    assert memvideo.align(img, src * 0.05) is None  # yeux à moins de 20 px
