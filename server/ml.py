"""Modèles locaux, chargés une fois par process (worker queue `ml`, ou API pour embed_text)."""
import os
import threading
from functools import cache

import numpy as np

# Worker en pool de threads : sans verrou, deux threads pourraient charger le même modèle (2x la RAM)
_load_lock = threading.RLock()  # réentrant : les catégories chargent CLIP sous le même verrou

# Multilingue par défaut : les requêtes sont en français. 512 dims, comme ViT-B/32.
CLIP_ARCH = os.environ.get("CLIP_ARCH", "xlm-roberta-base-ViT-B-32")
CLIP_PRETRAINED = os.environ.get("CLIP_PRETRAINED", "laion5b_s13b_b90k")
CLIP_MODEL_ID = f"{CLIP_ARCH}/{CLIP_PRETRAINED}"

MIN_DET_SCORE = 0.6
MIN_FACE_PX = 40  # sur la miniature md (1440 px) : en dessous, l'embedding est peu fiable


def _clip():
    with _load_lock:
        return _load_clip()


@cache
def _load_clip():
    import open_clip
    import torch

    model, _, preprocess = open_clip.create_model_and_transforms(CLIP_ARCH, pretrained=CLIP_PRETRAINED)
    model.eval()
    return model, preprocess, open_clip.get_tokenizer(CLIP_ARCH), torch


def embed_image(path) -> np.ndarray:
    from PIL import Image

    model, preprocess, _, torch = _clip()
    with torch.no_grad(), Image.open(path) as img:
        v = model.encode_image(preprocess(img.convert("RGB")).unsqueeze(0))[0]
    return (v / v.norm()).numpy()


def embed_text(query: str) -> np.ndarray:
    model, _, tokenizer, torch = _clip()
    with torch.no_grad():
        v = model.encode_text(tokenizer([query]))[0]
    return (v / v.norm()).numpy()


def _faces():
    with _load_lock:
        return _load_faces()


@cache
def _load_faces():
    from insightface.app import FaceAnalysis

    app = FaceAnalysis(name="buffalo_l", allowed_modules=["detection", "recognition"],
                       providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(640, 640))
    return app


def detect_faces(path) -> list[tuple[list[float], float, np.ndarray]]:
    """Retourne [(bbox normalisée x1,y1,x2,y2, score, embedding 512 normalisé)]."""
    import cv2

    img = cv2.imread(str(path))  # BGR, attendu par InsightFace
    if img is None:
        raise OSError(f"image illisible: {path}")
    h, w = img.shape[:2]
    out = []
    for f in _faces().get(img):
        x1, y1, x2, y2 = (float(c) for c in f.bbox)
        if f.det_score < MIN_DET_SCORE or min(x2 - x1, y2 - y1) < MIN_FACE_PX:
            continue
        bbox = [max(0.0, x1 / w), max(0.0, y1 / h), min(1.0, x2 / w), min(1.0, y2 / h)]
        out.append((bbox, float(f.det_score), f.normed_embedding))
    return out


# ---------- Catégories (CLIP zéro-shot, 100 % local) ----------
# Libellé affiché -> description donnée à CLIP. Ajouter une ligne suffit pour créer une catégorie
# (puis relancer tasks.categorize_all pour les photos existantes).
CATEGORIES = {
    "Plage": "une photo d'une plage au bord de la mer",
    "Montagne": "une photo de paysage de montagne",
    "Nature": "une photo de nature, de forêt ou de campagne",
    "Ville": "une photo d'une rue ou de bâtiments en ville",
    "Nourriture": "une photo d'un plat ou de nourriture",
    "Animaux": "une photo d'un animal, chien ou chat",
    "Bébés et enfants": "une photo d'un bébé ou d'un jeune enfant",
    "Selfies": "un selfie, un autoportrait pris avec un téléphone",
    "Groupes": "une photo de groupe de plusieurs personnes",
    "Fêtes": "une photo d'une fête ou d'un anniversaire avec un gâteau",
    "Neige": "une photo de neige en hiver",
    "Nuit": "une photo prise la nuit",
    "Voitures": "une photo d'une voiture",
    "Documents": "une photo d'un document, d'un ticket ou d'une facture",
    "Captures d'écran": "une capture d'écran de téléphone ou d'ordinateur",
}
CAT_MIN_PROB = float(os.environ.get("CAT_MIN_PROB", "0.35"))  # probabilité (softmax entre catégories)
CAT_MIN_SIM = float(os.environ.get("CAT_MIN_SIM", "0.12"))  # similarité brute : évite de classer l'inclassable


@cache
def _category_matrix() -> np.ndarray:
    with _load_lock:
        model, _, tokenizer, torch = _clip()
        with torch.no_grad():
            m = model.encode_text(tokenizer(list(CATEGORIES.values())))
        return (m / m.norm(dim=-1, keepdim=True)).numpy()


def categorize_many(vectors: np.ndarray) -> list[list[str]]:
    """Jusqu'à 2 catégories par vecteur CLIP normalisé (lignes de `vectors`)."""
    sims = vectors @ _category_matrix().T
    logits = 100 * sims  # échelle de température de CLIP
    probs = np.exp(logits - logits.max(axis=1, keepdims=True))
    probs /= probs.sum(axis=1, keepdims=True)
    names = list(CATEGORIES)
    out = []
    for p, s in zip(probs, sims):
        best = np.argsort(-p)[:2]
        out.append([names[i] for i in best if p[i] >= CAT_MIN_PROB and s[i] >= CAT_MIN_SIM])
    return out
