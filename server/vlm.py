"""Connecteur VLM : Gemini Flash (API, tier gratuit) ou Ollama (local). VLM_PROVIDER=gemini|ollama|off.

Rate-limit : géré par Celery (tasks.describe) — token bucket `rate_limit` + backoff exponentiel
sur RetryableError. Ce module ne fait que classer les erreurs.
ATTENTION vie privée : sur le tier gratuit, Google peut utiliser les contenus envoyés pour
améliorer ses produits. Ollama garde tout sur le NAS.
"""
import base64
import os
from functools import cache
from pathlib import Path

import httpx
from pydantic import BaseModel, Field

PROVIDER = os.environ.get("VLM_PROVIDER", "off")
ENABLED = PROVIDER in ("gemini", "ollama")

PROMPT = (
    "Analyse cette photo pour un moteur de recherche personnel. "
    "description : 1 à 3 phrases en français (lieu, scène, activité, objets notables). "
    "ocr_text : tout le texte lisible, tel quel ; chaîne vide s'il n'y en a pas. "
    "tags : 5 à 15 mots-clés courts en français, en minuscules."
)


class VlmResult(BaseModel):
    description: str
    ocr_text: str = ""
    tags: list[str] = Field(default_factory=list)


class RetryableError(Exception):
    """Quota (429), erreur serveur ou réseau : la tâche sera re-planifiée avec backoff."""


def describe(image_path) -> VlmResult:
    if PROVIDER == "gemini":
        return _gemini(Path(image_path).read_bytes())
    if PROVIDER == "ollama":
        return _ollama(image_path)
    raise RuntimeError(f"VLM_PROVIDER invalide: {PROVIDER!r}")


@cache
def _gemini_client():
    from google import genai

    return genai.Client(api_key=os.environ["GEMINI_API_KEY"])


def _gemini(webp: bytes) -> VlmResult:
    from google.genai import errors, types

    try:
        resp = _gemini_client().models.generate_content(
            model=os.environ.get("GEMINI_MODEL", "gemini-2.5-flash"),
            contents=[types.Part.from_bytes(data=webp, mime_type="image/webp"), PROMPT],
            config=types.GenerateContentConfig(
                response_mime_type="application/json", response_schema=VlmResult, temperature=0.2
            ),
        )
    except errors.APIError as e:
        if e.code == 429 or e.code >= 500:
            raise RetryableError(f"gemini {e.code}: {e}") from e
        raise  # 400/403 : clé ou requête invalide, inutile de réessayer
    except httpx.TransportError as e:
        raise RetryableError(f"gemini réseau: {e}") from e
    if resp.parsed is not None:
        return resp.parsed
    # Réponse bloquée (filtre de sécurité) : on marque le média traité plutôt que de boucler.
    return VlmResult.model_validate_json(resp.text) if resp.text else VlmResult(description="")


def _ollama(image_path) -> VlmResult:
    import pyvips

    jpeg = pyvips.Image.new_from_file(str(image_path)).jpegsave_buffer(Q=85)
    try:
        r = httpx.post(
            f"{os.environ.get('OLLAMA_URL', 'http://ollama:11434')}/api/generate",
            json={
                "model": os.environ.get("OLLAMA_MODEL", "qwen2.5vl:7b"),
                "prompt": PROMPT,
                "images": [base64.b64encode(jpeg).decode()],
                "format": VlmResult.model_json_schema(),
                "stream": False,
                "options": {"temperature": 0.2},
            },
            timeout=300,
        )
        r.raise_for_status()
    except httpx.HTTPError as e:  # service local : toute erreur HTTP/réseau est transitoire
        raise RetryableError(f"ollama: {e}") from e
    return VlmResult.model_validate_json(r.json()["response"])


def gemini_enabled() -> bool:
    return bool(os.environ.get("GEMINI_API_KEY"))


def gemini_enhance(jpeg: bytes) -> bytes:
    """Retouche par le modèle d'image de Gemini ; renvoie l'image produite (PNG ou JPEG).
    Envoie la photo à Google : uniquement sur demande explicite de l'utilisateur (bouton dans l'app)."""
    from google.genai import errors, types

    try:
        resp = _gemini_client().models.generate_content(
            model=os.environ.get("GEMINI_IMAGE_MODEL", "gemini-2.5-flash-image"),
            contents=[types.Part.from_bytes(data=jpeg, mime_type="image/jpeg"),
                      "Améliore cette photo de façon naturelle : exposition, couleurs, netteté, bruit. "
                      "Ne change ni le cadrage, ni les personnes, ni le contenu."],
            config=types.GenerateContentConfig(response_modalities=["IMAGE"]),
        )
    except errors.APIError as e:
        if e.code == 429 or e.code >= 500:
            raise RetryableError(f"gemini {e.code}: {e}") from e
        raise
    for part in resp.candidates[0].content.parts if resp.candidates else []:
        if part.inline_data and part.inline_data.data:
            return part.inline_data.data
    raise RuntimeError("Gemini n'a pas renvoyé d'image (contenu refusé ou quota épuisé)")
