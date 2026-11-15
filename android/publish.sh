#!/bin/sh
# Compile l'APK release (signée avec memoriesbk-release.jks : ne jamais la perdre) et le publie sur le serveur ; l'app installée propose la mise à jour au prochain lancement.
set -e
cd "$(dirname "$0")"
./gradlew -q assembleRelease
# Serveur actif : la Freebox relaie vers 192.168.1.58 quand celui-ci est allumé (voir deploy/photos-switch)
H=$(ssh freebox@192.168.1.41 'docker ps -q -f name=photos-forward' | grep -q . && echo devil@192.168.1.58 || echo freebox@192.168.1.41)
scp -q app/build/outputs/apk/release/app-release.apk $H:photos/server/web/MemoriesBK.apk
scp -q app/build/apk-version.txt $H:photos/server/web/MemoriesBK.version
ssh $H 'cd photos && docker compose cp server/web/MemoriesBK.apk api:/app/web/ && docker compose cp server/web/MemoriesBK.version api:/app/web/'
echo "publié : version $(cat app/build/apk-version.txt)"
