#!/bin/sh
# Compile l'APK release (signée avec memoriesbk-release.jks : ne jamais la perdre) et le publie sur le serveur ; l'app installée propose la mise à jour au prochain lancement.
set -e
cd "$(dirname "$0")"
./gradlew -q assembleRelease
scp -q app/build/outputs/apk/release/app-release.apk freebox@192.168.1.41:photos/server/web/MemoriesBK.apk
scp -q app/build/apk-version.txt freebox@192.168.1.41:photos/server/web/MemoriesBK.version
ssh freebox@192.168.1.41 'cd photos && sudo docker compose cp server/web/MemoriesBK.apk api:/app/web/ && sudo docker compose cp server/web/MemoriesBK.version api:/app/web/'
echo "publié : version $(cat app/build/apk-version.txt)"
