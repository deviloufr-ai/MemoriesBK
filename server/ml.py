"""Modèles locaux, chargés une fois par process (worker queue `ml`, ou API pour embed_text)."""
import os
import threading
from functools import cache
from pathlib import Path

import numpy as np

# Worker en pool de threads : sans verrou, deux threads pourraient charger le même modèle (2x la RAM)
_load_lock = threading.RLock()  # réentrant : les catégories chargent CLIP sous le même verrou

# Multilingue par défaut : les requêtes sont en français. 512 dims, comme ViT-B/32.
CLIP_ARCH = os.environ.get("CLIP_ARCH", "xlm-roberta-base-ViT-B-32")
CLIP_PRETRAINED = os.environ.get("CLIP_PRETRAINED", "laion5b_s13b_b90k")
CLIP_MODEL_ID = f"{CLIP_ARCH}/{CLIP_PRETRAINED}"

# Modèle de recherche plus gros, en plus du précédent (serveur 30 Go seulement) : sert uniquement à la recherche
# par texte. Catégories, doublons, animaux, nettoyage restent sur CLIP_MODEL_ID (seuils calibrés dessus), que la
# Freebox sait aussi calculer. Vide = désactivé. Table search_embeddings : vector(1024).
SEARCH_CLIP_ARCH = os.environ.get("SEARCH_CLIP_ARCH", "")
SEARCH_CLIP_PRETRAINED = os.environ.get("SEARCH_CLIP_PRETRAINED", "")
SEARCH_MODEL_ID = f"{SEARCH_CLIP_ARCH}/{SEARCH_CLIP_PRETRAINED}" if SEARCH_CLIP_ARCH else None
SEARCH_DIMS = 1024

MIN_DET_SCORE = 0.6
MIN_FACE_PX = 40  # dans l'image analysée (miniature md, ou original réduit) : en dessous, l'embedding est peu fiable


def _clip(search: bool = False):
    with _load_lock:
        if search:
            assert SEARCH_MODEL_ID, "SEARCH_CLIP_ARCH non défini"
            return _load_clip(SEARCH_CLIP_ARCH, SEARCH_CLIP_PRETRAINED)
        return _load_clip(CLIP_ARCH, CLIP_PRETRAINED)


@cache
def _load_clip(arch: str, pretrained: str):
    import open_clip
    import torch

    from open_clip.pretrained import download_pretrained, get_pretrained_cfg

    # VM 2 Go : un chargement classique copie 1,5 Go de poids en RAM anonyme (OOM en boucle le 10/11).
    # Modèle construit sur `meta` puis poids pris en mmap : pages adossées au fichier, que le noyau peut libérer.
    cfg = get_pretrained_cfg(arch, pretrained)
    with torch.device("meta"):
        model = open_clip.create_model(arch, pretrained_hf=False, device="meta")
    sd = torch.load(download_pretrained(cfg), map_location="cpu", mmap=True, weights_only=True)
    sd = {k.removeprefix("module."): v for k, v in sd.get("state_dict", sd).items()}
    model.load_state_dict(sd, assign=True, strict=False)
    emb = getattr(getattr(model, "text", None), "transformer", None)
    emb = getattr(emb, "embeddings", None)
    if emb is not None:  # buffers non sauvegardés dans le checkpoint (XLM-RoBERTa) : à recréer
        emb.position_ids = torch.arange(emb.position_embeddings.num_embeddings).expand((1, -1))
        emb.token_type_ids = torch.zeros_like(emb.position_ids)
    missing = [n for n, t in [*model.named_parameters(), *model.named_buffers()] if t.is_meta]
    assert not missing, f"poids CLIP absents du checkpoint : {missing}"
    model.eval()
    preprocess = open_clip.image_transform(model.visual.image_size, is_train=False,
                                           mean=cfg.get("mean"), std=cfg.get("std"))
    return model, preprocess, open_clip.get_tokenizer(arch), torch


def embed_image(path, search: bool = False) -> np.ndarray:
    from PIL import Image

    model, preprocess, _, torch = _clip(search)
    with torch.no_grad(), Image.open(path) as img:
        v = model.encode_image(preprocess(img.convert("RGB")).unsqueeze(0))[0]
    return (v / v.norm()).numpy()


def embed_text(query: str, search: bool = False) -> np.ndarray:
    model, _, tokenizer, torch = _clip(search)
    with torch.no_grad():
        v = model.encode_text(tokenizer([query]))[0]
    return (v / v.norm()).numpy()


def _faces():
    with _load_lock:
        return _load_faces()


# Taille d'analyse du détecteur : 640 suffit sur une miniature 1440 px ; sur une image plus grande (FACE_SOURCE_PX,
# serveur 30 Go) 1280 garde les petits visages (mesuré : plan large, 20 visages sur 29 à 640, 29 à 960 et plus).
FACE_DET_SIZE = int(os.environ.get("FACE_DET_SIZE", "640"))


@cache
def _load_faces():
    from insightface.app import FaceAnalysis

    app = FaceAnalysis(name="buffalo_l", allowed_modules=["detection", "recognition"],
                       providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(FACE_DET_SIZE, FACE_DET_SIZE))
    return app


def _read_bgr(path, max_px: int) -> np.ndarray:
    """Image BGR 8 bits pour InsightFace. max_px : original réduit (orientation EXIF appliquée, HEIC compris)."""
    if not max_px:
        import cv2

        img = cv2.imread(str(path))
        if img is None:
            raise OSError(f"image illisible: {path}")
        return img
    import pyvips

    try:
        img = pyvips.Image.thumbnail(str(path), max_px, height=max_px, size="down")
        if img.hasalpha():
            img = img.flatten()
        img = img.colourspace("srgb").cast("uchar")
        return np.ascontiguousarray(img.numpy()[:, :, 2::-1])  # RGB -> BGR
    except pyvips.Error as e:
        raise OSError(f"image illisible: {path}: {e}") from e


def detect_faces(path, max_px: int = 0) -> list[tuple[list[float], float, np.ndarray]]:
    """Retourne [(bbox normalisée x1,y1,x2,y2, score, embedding 512 normalisé)].
    MIN_FACE_PX s'entend dans l'image analysée : sur un original réduit à 3072 px, un visage de 40 px fait ~19 px
    sur la miniature md, et son embedding est calculé sur de vrais pixels."""
    img = _read_bgr(path, max_px)
    h, w = img.shape[:2]
    out = []
    for f in _faces().get(img):
        x1, y1, x2, y2 = (float(c) for c in f.bbox)
        if f.det_score < MIN_DET_SCORE or min(x2 - x1, y2 - y1) < MIN_FACE_PX:
            continue
        bbox = [max(0.0, x1 / w), max(0.0, y1 / h), min(1.0, x2 / w), min(1.0, y2 / h)]
        out.append((bbox, float(f.det_score), f.normed_embedding))
    return out


# Détourage des personnes (U²-Net human seg, ~170 Mo) : téléchargé une fois dans un dossier persistant
SEG_URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net_human_seg.onnx"
SEG_PATH = Path(os.environ.get("MODELS_DIR", "/thumbs/models")) / "u2net_human_seg.onnx"


@cache
def _seg():
    import httpx
    import onnxruntime as ort

    if not SEG_PATH.exists():
        SEG_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp = SEG_PATH.with_suffix(".part")
        with httpx.stream("GET", SEG_URL, follow_redirects=True, timeout=120) as r, open(tmp, "wb") as f:
            r.raise_for_status()
            for chunk in r.iter_bytes(1 << 20):
                f.write(chunk)
        tmp.replace(SEG_PATH)
    return ort.InferenceSession(str(SEG_PATH), providers=["CPUExecutionProvider"])


def person_mask(bgr: np.ndarray) -> np.ndarray:
    """Masque 0..1 (float32, taille de l'image) des personnes de l'image BGR."""
    import cv2

    h, w = bgr.shape[:2]
    x = cv2.resize(bgr, (320, 320), interpolation=cv2.INTER_AREA)[:, :, ::-1].astype(np.float32)
    x = (x / max(float(x.max()), 1.0) - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    with _load_lock:
        s = _seg()
    p = s.run(None, {s.get_inputs()[0].name: x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0, 0]
    p = (p - p.min()) / max(float(p.max() - p.min()), 1e-6)
    return cv2.resize(p.astype(np.float32), (w, h), interpolation=cv2.INTER_LINEAR)


def face_landmarks(path, max_px: int = 0) -> tuple[np.ndarray, list[tuple[list[float], np.ndarray]]]:
    """(image BGR lue, [(bbox normalisée, 5 points en px de cette image)]) : pour aligner un visage (timelapse)."""
    img = _read_bgr(path, max_px)
    h, w = img.shape[:2]
    return img, [([f.bbox[0] / w, f.bbox[1] / h, f.bbox[2] / w, f.bbox[3] / h], f.kps)
                 for f in _faces().get(img) if f.det_score >= MIN_DET_SCORE]


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
