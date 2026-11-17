#!/bin/sh
# Déploie le serveur sur la VM depuis git : uniquement du code commité ET poussé, pour que le serveur ne fasse
# jamais tourner un code qui n'existe nulle part ailleurs. L'APK se publie à part : android/publish.sh.
#   ./deploy.sh            (vers le serveur actif : voir deploy/photos-switch ; forcer avec SSH_HOST=...)
set -e
cd "$(dirname "$0")"
# La Freebox relaie vers 192.168.1.58 quand celui-ci est actif (conteneur photos-forward)
HOST="${SSH_HOST:-$(ssh freebox@192.168.1.41 'docker ps -q -f name=photos-forward' | grep -q . && echo devil@192.168.1.58 || echo freebox@192.168.1.41)}"

[ -z "$(git status --porcelain)" ] || { echo "Changements non commités : committer d'abord." >&2; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD n'est pas origin/main : git push (ou pull) d'abord." >&2; exit 1; }
REV=$(git rev-parse --short HEAD)

# Fichiers suivis seulement : .env, APK, données restent intacts sur la VM ; les tests tournent avant le redémarrage
git archive HEAD server db docker-compose.yml | ssh "$HOST" "tar -x -C photos && echo $REV > photos/server/REVISION"
# Réglages 30 Go (Ollama, worker-vlm…) : seulement sur le gros serveur, jamais sur la Freebox
case "$HOST" in *192.168.1.58) git show HEAD:deploy/docker-compose.big.yml | ssh "$HOST" "cat > photos/docker-compose.override.yml" ;; esac
ssh "$HOST" "cd photos && docker compose build -q worker api && docker compose run --rm --no-deps -T api python -m pytest -q test_util.py test_metadata.py test_faces.py test_integration.py"
ssh "$HOST" "cd photos && docker compose up -d --remove-orphans && docker image prune -f >/dev/null"

i=0
until ssh "$HOST" "curl -sf localhost:8080/api/v1/health" | grep -q "\"$REV\""; do
  i=$((i + 1)); [ $i -lt 40 ] || { echo "L'API ne répond pas avec la version $REV (voir docker compose logs api)." >&2; exit 1; }
  sleep 3
done
echo "Déployé : $REV"
