FROM python:3.12-slim-bookworm

# build-essential : insightface n'est distribué qu'en sources (extension Cython)
RUN apt-get update && apt-get install -y --no-install-recommends \
      libvips42 libimage-exiftool-perl ffmpeg tzdata build-essential libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# torch CPU (~200 Mo) au lieu de la build CUDA (~2 Go) ; retirer --index-url pour un GPU NVIDIA
RUN pip install --no-cache-dir torch==2.5.1 torchvision==0.20.1 --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .

# Fuseau de l'utilisateur : sert à convertir les dates vidéo (QuickTime UTC) en heure locale
ENV TZ=Europe/Paris
# Celery ne garde pas /app dans sys.path après le chargement de -A tasks : les `import ml` différés échoueraient
ENV PYTHONPATH=/app
CMD ["celery", "-A", "tasks", "worker", "-Q", "media", "--concurrency", "4"]
