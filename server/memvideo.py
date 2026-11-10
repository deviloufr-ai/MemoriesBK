"""Vidéo souvenir du mois : choix des médias et montage ffmpeg (1280x720 paysage, ~1 min, musique optionnelle)."""
import math
import random
import subprocess
import tempfile
from datetime import date
from pathlib import Path

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
        final = tmp / "final.mp4"
        if music:
            _ffmpeg(["-i", str(silent), "-stream_loop", "-1", "-i", str(music), "-map", "0:v", "-map", "1:a",
                     "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
                     "-af", f"afade=t=in:d=1,afade=t=out:st={max(total - 2.5, 0)}:d=2.5",
                     "-t", f"{total:.2f}", "-movflags", "+faststart", str(final)])
        else:
            _ffmpeg(["-i", str(silent), "-c", "copy", "-movflags", "+faststart", str(final)])
        final.replace(out)
    return total
