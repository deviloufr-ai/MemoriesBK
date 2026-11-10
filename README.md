# MemoriesBK

**Une photothèque familiale auto-hébergée, alternative à Google Photos : les souvenirs restent à la maison, l'intelligence artificielle aussi.**

MemoriesBK sauvegarde automatiquement les photos et vidéos des téléphones de la famille sur un NAS personnel, les organise grâce à une IA qui tourne en local, et permet de les revoir et de les partager en famille. L'ensemble fonctionne sur une petite machine virtuelle de 2 Go de RAM hébergée sur une Freebox.

## Fonctionnalités

### Sauvegarde et bibliothèque
- **Sauvegarde automatique** depuis Android, en tâche de fond. L'envoi reprend là où il s'était arrêté, et une empreinte SHA-256 évite les doublons.
- **Import depuis le NAS** de bibliothèques existantes (des dizaines de milliers de fichiers). Un fichier déjà connu est reconnu par son nom et sa taille sans être relu.
- **Dates fiables** : une date EXIF aberrante est écartée, puis la date est retrouvée dans le nom du fichier.
- **Chronologie fluide** : navigation par mois d'un glissement de doigt, barre de défilement rapide, zoom par pincement ou double tap.
- **Albums, lieux** (géocodage hors ligne), **ajouts récents** et **corbeille** restaurable.
- **Libérer de l'espace** : supprime du téléphone les photos déjà sauvegardées.

### Intelligence artificielle locale
- **Recherche en langage naturel** (« coucher de soleil à la plage »), avec le modèle multilingue CLIP.
- **Reconnaissance des visages** (InsightFace) : regroupement par personne et correction manuelle d'un visage mal attribué.
- **Catégories automatiques** : plage, nourriture, animaux, documents…
- **Nettoyage assisté** : photos floues, trop sombres, rafales en double, captures d'écran, ou suppression par description.
- **Retouche** automatique sur le serveur, ou par Gemini si l'utilisateur l'active.

### Souvenirs
- **« Il y a N ans »** : les photos de la même semaine les années précédentes.
- **Vidéo souvenir mensuelle** générée automatiquement : sélection des meilleurs moments, effet Ken Burns stabilisé, titre et musique (ffmpeg).

### Famille et partage
- **Comptes familiaux** avec rôles. L'**administrateur** gère les membres.
- **Partage limité par règles** : par exemple, « grand-mère ne voit que les photos des enfants depuis 2015 ». Une règle combine des dates, des personnes reconnues et des albums, et le filtre est appliqué par le serveur sur chaque requête.
- **J'aime et commentaires** sur chaque photo ou vidéo.
- **« Ajoutée par »** : on voit quel membre a ajouté chaque photo.

### Application Android
- Kotlin et Jetpack Compose (Material 3), en **français et en japonais**.
- **Double réseau** : l'app passe par le Wi-Fi de la maison quand c'est possible, sinon par Internet via Tailscale Funnel, sans aucun réglage.
- **Mises à jour intégrées** : l'app détecte une nouvelle version publiée sur le serveur et propose de l'installer.
- **Partage de l'app** : envoi du lien de téléchargement à un proche en un geste.
- **Lecteur vidéo** plein écran : l'interface se masque d'elle-même et la lecture continue quand on tourne l'écran.

### Version web
- Accès depuis un navigateur, avec les mêmes comptes et le même partage.

## Architecture

| Couche | Technologies |
|---|---|
| App mobile | Kotlin, Jetpack Compose, Hilt, Retrofit, WorkManager, Media3/ExoPlayer, Coil |
| API | Python, FastAPI, JWT |
| Traitements | Celery (files `media`, `ml`, `vlm`), Redis |
| Données | PostgreSQL 16 + pgvector (recherche vectorielle), NAS pour les originaux, SSD pour les miniatures |
| IA | open_clip (XLM-RoBERTa ViT-B/32), InsightFace, libvips, ffmpeg, Gemini (optionnel) |
| Hébergement | Docker Compose sur une VM Freebox (2 Go de RAM), Tailscale Funnel pour l'accès HTTPS |

Quelques choix d'ingénierie :
- **Tenir sur 2 Go de RAM** : un seul worker Celery avec un pool de threads charge les modèles une seule fois. L'analyse IA se met en pause automatiquement pendant les gros imports. Des limites mémoire Docker garantissent qu'un dépassement ne redémarre que le worker, jamais la VM.
- **Ingestion sûre** : les fichiers passent par une quarantaine, puis rejoignent la bibliothèque par un `rename` atomique. Un verrou Redis empêche qu'un même import tourne deux fois.
- **Confidentialité** : les photos et l'IA restent sur le réseau domestique. Gemini n'est utilisé que sur demande explicite.

Détails : [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · API : [docs/API.md](docs/API.md)

## Démarrage

```bash
cp .env.example .env        # renseigner les mots de passe et chemins
docker compose up -d --build
docker compose exec api python api.py create-user moi@example.com
```

Application Android : `android/publish.sh` compile l'APK et le publie sur le serveur. La clé de signature (`*.jks`, `keystore.properties`) n'est pas versionnée.
