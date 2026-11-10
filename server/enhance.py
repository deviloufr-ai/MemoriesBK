"""Retouche automatique locale (aucun envoi hors du serveur) : lumière, dominante de couleur, saturation, netteté."""
from pathlib import Path

import numpy as np


def load_rgb(src: Path) -> np.ndarray:
    """Image orientée (EXIF) en RGB 8 bits, quel que soit le format d'entrée (HEIC, PNG 16 bits, niveaux de gris…)."""
    import pyvips

    img = pyvips.Image.new_from_file(str(src)).autorot()
    if img.hasalpha():
        img = img.flatten(background=255)
    img = img.colourspace("srgb")
    if img.format != "uchar":
        img = img.cast("uchar")
    return np.ndarray(buffer=img.write_to_memory(), dtype=np.uint8, shape=[img.height, img.width, img.bands])[:, :, :3]


def enhance_rgb(rgb: np.ndarray) -> np.ndarray:
    import cv2

    lab = cv2.cvtColor(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    # Contraste local (CLAHE) mélangé à 60 % : débouche les ombres sans effet « HDR »
    l = cv2.addWeighted(l, 0.4, cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(l), 0.6, 0)
    # Balance des blancs « monde gris » à 50 % : atténue une dominante sans gommer l'ambiance (coucher de soleil)
    a = np.clip(a.astype(np.int16) + round((128 - float(a.mean())) * 0.5), 0, 255).astype(np.uint8)
    b = np.clip(b.astype(np.int16) + round((128 - float(b.mean())) * 0.5), 0, 255).astype(np.uint8)
    bgr = cv2.cvtColor(cv2.merge([l, a, b]), cv2.COLOR_LAB2BGR)
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    hsv[..., 1] = np.clip(hsv[..., 1].astype(np.float32) * 1.08, 0, 255).astype(np.uint8)
    bgr = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)
    blur = cv2.GaussianBlur(bgr, (0, 0), 1.2)
    bgr = cv2.addWeighted(bgr, 1.35, blur, -0.35, 0)  # masque flou léger
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def save_jpeg(rgb: np.ndarray, dst: Path) -> None:
    import cv2

    cv2.imwrite(str(dst), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 92])


def quality(path: Path) -> tuple[float, float]:
    """(netteté, luminosité) d'une miniature : variance du laplacien sur 1024 px, luminance moyenne 0..255."""
    import cv2

    gray = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if gray is None:
        raise OSError(f"image illisible: {path}")
    scale = 1024 / max(gray.shape)
    if scale < 1:  # même échelle pour toutes les photos, sinon le score dépend de la résolution
        gray = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var()), float(gray.mean())
