# MemoriesBK — texte de présentation (portfolio)

## Version courte

**MemoriesBK** est une photothèque familiale auto-hébergée, pensée comme une alternative privée à Google Photos. Les photos des téléphones sont sauvegardées automatiquement sur un NAS personnel, organisées par une IA locale qui retrouve les visages, les lieux et les scènes, puis partagées en famille avec un contrôle fin de qui voit quoi. Le projet comprend une app Android native, un serveur Python et une version web, et tient sur une machine virtuelle de 2 Go de RAM.

## Version longue

Avec des dizaines de milliers de photos de famille réparties entre plusieurs téléphones et un NAS, je voulais retrouver le confort de Google Photos sans confier nos souvenirs à un service tiers. J'ai donc conçu et développé **MemoriesBK** de bout en bout.

**Ce que fait l'application**
- Sauvegarde automatique et reprenable des photos et vidéos depuis Android, sans doublon, et import de bibliothèques existantes depuis le NAS.
- Recherche en langage naturel (« anniversaire dans le jardin ») et reconnaissance des visages, avec des modèles d'IA exécutés localement (CLIP multilingue, InsightFace).
- Souvenirs « il y a N ans » et vidéo souvenir mensuelle montée automatiquement : sélection des meilleurs moments, effet Ken Burns et musique.
- Nettoyage assisté de la bibliothèque : photos floues, sombres, rafales en double, captures d'écran.
- Espace famille : comptes et rôles, likes et commentaires, et **partage limité par règles** que l'administrateur définit (« les photos des enfants depuis 2015, pour leur grand-mère »).
- App Android en Kotlin et Jetpack Compose, en français et en japonais, avec mises à jour intégrées et bascule automatique entre le réseau local et Internet.

**Les défis techniques**
- **Faire tourner une IA sur 2 Go de RAM** : un worker unique charge les modèles une seule fois, l'analyse se met en pause pendant les gros imports, et des limites mémoire évitent qu'un pic ne fige le serveur.
- **Fiabilité de l'ingestion** sur plus de 45 000 fichiers : quarantaine puis déplacement atomique, verrou contre les imports en double, et récupération des dates à partir des noms de fichiers quand l'EXIF est faux.
- **Sécurité du partage** : la visibilité est filtrée côté serveur, dans chaque requête SQL (fonction PostgreSQL), et vérifiée par des tests d'accès.

**Stack** : Kotlin · Jetpack Compose · Hilt · Media3 · Python · FastAPI · Celery · Redis · PostgreSQL + pgvector · open_clip · InsightFace · ffmpeg · Docker · Tailscale
