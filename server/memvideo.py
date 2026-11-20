"""Vidéo souvenir du mois : choix des médias et montage ffmpeg (1280x720 paysage, ~1 min, musique optionnelle)."""
import math
import random
import subprocess
import tempfile
from datetime import date
from pathlib import Path

import numpy as np

W, H, FPS = 1280, 720, 25
ZOOM_SS = 3  # zoompan arrondit x/y au pixel : sur une image 3x plus grande, l'à-coup devient invisible
CLIP_S = 3.0  # durée de chaque photo / extrait vidéo
TITLE_S = 2.5
MAX_PHOTOS, MAX_VIDEOS, MIN_ITEMS = 16, 3, 4
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
MUSIC_EXT = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"}
MONTHS_FR = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août",
             "septembre", "octobre", "novembre", "décembre"]


def spread_pick(items: list, n: int, score) -> list:
    """Les n meilleurs « par tranche de temps » : items triés par date, découpés en n tranches, le meilleur de chacune.
    Évite 10 photos de la même rafale tout en gardant les plus belles."""
    if len(items) <= n:
        return list(items)
    bounds = [round(i * len(items) / n) for i in range(n + 1)]
    return [max(items[a:b], key=score) for a, b in zip(bounds, bounds[1:]) if b > a]


assert [x for x in spread_pick(list(range(10)), 3, lambda v: v % 4)] == [2, 3, 7]


def photo_score(m: dict) -> float:
    # visages de la famille d'abord, puis netteté (log : au-delà d'un certain seuil, toutes les photos sont nettes)
    return 3 * min(m.get("faces") or 0, 3) + math.log1p(m.get("sharpness") or 0)


def title(month: date) -> str:
    return f"{MONTHS_FR[month.month - 1].capitalize()} {month.year}"


def _fit(src_label: str) -> str:
    """Image entière au centre, sur un fond flou de la même image (pas de bandes noires en vertical)."""
    return (f"[{src_label}]split[a][b];"
            f"[a]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},boxblur=20:2,eq=brightness=-0.08[bg];"
            f"[b]scale={W}:{H}:force_original_aspect_ratio=decrease[fg];"
            f"[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1")


FADE = f"fade=in:0:{int(FPS * 0.4)},fade=out:st={CLIP_S - 0.4}:d=0.4"
ENC = ["-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-r", str(FPS), "-threads", "2"]


def _ffmpeg(args: list, timeout=600):
    subprocess.run(["ffmpeg", "-y", "-v", "error", *args], check=True, timeout=timeout)


def photo_clip(src: Path, out: Path):
    zoom = (f"scale={W * ZOOM_SS}:{H * ZOOM_SS}:flags=bicubic,"  # zoom lent (Ken Burns), sans tremblement
            f"zoompan=z='1+0.0012*on':d=1:x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':s={W}x{H}:fps={FPS}")
    _ffmpeg(["-loop", "1", "-t", str(CLIP_S), "-i", str(src),
             "-filter_complex", f"{_fit('0:v')},{zoom},{FADE}", "-t", str(CLIP_S), *ENC, str(out)])


def video_clip(src: Path, duration_s: float, out: Path):
    start = max(duration_s / 2 - CLIP_S / 2, 0)  # le milieu est en général le moment intéressant
    _ffmpeg(["-ss", f"{start:.2f}", "-t", str(CLIP_S), "-i", str(src),
             "-filter_complex", f"{_fit('0:v')},fps={FPS},{FADE}", "-t", str(CLIP_S), *ENC, str(out)])


def title_clip(text: str, out: Path):
    draw = (f"drawtext=fontfile={FONT}:text='{text}':fontcolor=white:fontsize=64:x=(w-text_w)/2:y=(h-text_h)/2,"
            f"fade=in:0:10,fade=out:st={TITLE_S - 0.5}:d=0.5")
    _ffmpeg(["-f", "lavfi", "-i", f"color=c=black:s={W}x{H}:r={FPS}:d={TITLE_S}", "-vf", draw, *ENC, str(out)])


def pick_music(music_dir: Path) -> Path | None:
    try:
        tracks = [p for p in music_dir.rglob("*") if p.suffix.lower() in MUSIC_EXT and p.is_file()]
    except OSError:
        return None
    return random.choice(tracks) if tracks else None


def render(month: date, clips: list[tuple[str, Path, float]], out: Path, music: Path | None) -> float:
    """clips : (kind, source, durée vidéo) dans l'ordre chronologique. Retourne la durée totale (s)."""
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as tmp:
        tmp = Path(tmp)
        parts = [tmp / "000.mp4"]
        title_clip(title(month), parts[0])
        for i, (kind, src, dur) in enumerate(clips, 1):
            part = tmp / f"{i:03}.mp4"
            try:
                (video_clip(src, dur, part) if kind == "video" else photo_clip(src, part))
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                continue  # un fichier illisible ne doit pas faire échouer toute la vidéo
            parts.append(part)
        (tmp / "list.txt").write_text("".join(f"file '{p.name}'\n" for p in parts))
        total = TITLE_S + CLIP_S * (len(parts) - 1)
        silent = tmp / "silent.mp4"
        _ffmpeg(["-f", "concat", "-safe", "0", "-i", str(tmp / "list.txt"), "-c", "copy", str(silent)])
        _finish(silent, total, music, tmp / "final.mp4").replace(out)
    return total


def _finish(silent: Path, total: float, music: Path | None, final: Path) -> Path:
    """Ajoute la musique (en boucle, fondu d'entrée et de sortie) ou recopie telle quelle, lecture rapide en streaming."""
    if music:
        _ffmpeg(["-i", str(silent), "-stream_loop", "-1", "-i", str(music), "-map", "0:v", "-map", "1:a",
                 "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
                 "-af", f"afade=t=in:d=1,afade=t=out:st={max(total - 2.5, 0)}:d=2.5",
                 "-t", f"{total:.2f}", "-movflags", "+faststart", str(final)])
    else:
        _ffmpeg(["-i", str(silent), "-c", "copy", "-movflags", "+faststart", str(final)])
    return final


# ---------- Timelapse « grandir » : le meilleur visage de chaque semaine, aligné sur les yeux ----------
TL_S, TL_FPS, TL_HOLD = 720, 6, 1.5  # carré 720 px, 6 visages par seconde en fondu, dernière image tenue 1,5 s
TL_MAX = 480                         # ~80 s au plus : au-delà, une semaine sur deux, etc.
TL_MIN = 8
# Yeux, nez, coins de la bouche du modèle ArcFace (112 px), recentrés : écart des yeux = 20 % de l'image
_ARC = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]])
TL_DST = ((_ARC - [56.0, 72.0]) * (TL_S * 0.2 / 35.24) + [TL_S / 2, TL_S * 0.46]).astype(np.float32)


def weekly(rows: list, score) -> list[list]:
    """Candidats groupés par semaine ISO de taken_at, meilleurs d'abord ; semaines dans l'ordre chronologique."""
    by = {}
    for r in rows:
        by.setdefault(tuple(r["taken_at"].isocalendar()[:2]), []).append(r)
    return [sorted(by[k], key=score, reverse=True) for k in sorted(by)]


def align(img: np.ndarray, kps: np.ndarray):
    """Image BGR -> carré TL_S où les 5 points tombent sur TL_DST. None si visage trop petit ou de profil."""
    import cv2

    kps = np.asarray(kps, dtype=np.float32)
    eye = kps[1] - kps[0]
    d2 = float(eye @ eye)
    if d2 < 20 ** 2:  # moins de 20 px entre les yeux : flou une fois agrandi
        return None
    if abs(float((kps[2] - (kps[0] + kps[1]) / 2) @ eye) / d2) > 0.2:  # nez loin du milieu des yeux : de profil
        return None
    m, _ = cv2.estimateAffinePartial2D(kps, TL_DST, method=cv2.LMEDS)
    return None if m is None else cv2.warpAffine(img, m, (TL_S, TL_S), flags=cv2.INTER_LINEAR)


def caption(bgr: np.ndarray, when: date):
    """Image PIL avec « Mars 2021 » en bas, lisible sur tout fond (contour noir)."""
    from PIL import Image, ImageDraw, ImageFont

    im = Image.fromarray(np.ascontiguousarray(bgr[:, :, ::-1]))
    d, f = ImageDraw.Draw(im), ImageFont.truetype(FONT, 34)
    text = title(when)
    d.text(((TL_S - d.textlength(text, font=f)) / 2, TL_S - 64), text, font=f, fill="white",
           stroke_width=3, stroke_fill="black")
    return im


def render_timelapse(frames: Path, n: int, out: Path, music: Path | None) -> float:
    """frames/00000.jpg… -> mp4 : fondu enchaîné entre visages, dernière image tenue. Retourne la durée (s)."""
    total = n / TL_FPS + TL_HOLD
    silent = frames / "silent.mp4"
    _ffmpeg(["-framerate", str(TL_FPS), "-i", str(frames / "%05d.jpg"), "-vf",
             f"framerate=fps={FPS}:interp_start=0:interp_end=255:scene=100,"
             f"tpad=stop_mode=clone:stop_duration={TL_HOLD},format=yuv420p", *ENC, str(silent)], timeout=1800)
    _finish(silent, total, music, frames / "final.mp4").replace(out)
    return total
