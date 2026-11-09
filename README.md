# Mes Cours

Application Android, dans l'esprit de Knowunity, pour réviser les cours des enfants :

1. **Photographier** les pages du cahier ou du manuel.
2. L'IA **lit le cours et le découpe en thèmes** : une fiche par thème avec résumé, points à
   retenir, vocabulaire et exemples, rangée par enfant et par matière.
3. Sur chaque fiche, **quiz** (QCM avec indice, correction et score) et **exercices**
   progressifs avec indice et correction détaillée.

## Trois moteurs IA

| | Gemini | NotebookLM intégré (expérimental) | NotebookLM via passerelle |
|---|---|---|---|
| Fonctionnement | L'app appelle l'API Gemini | L'app parle directement à NotebookLM grâce à la bibliothèque native [`nlm-core`](nlm-core/README.md) (Rust) | L'app passe par la [passerelle](bridge/README.md) Python dans PocketDock ou sur un PC |
| À installer | Une clé gratuite sur [aistudio.google.com](https://aistudio.google.com) | Rien : connexion Google dans l'app | PocketDock ou un PC allumé |
| Résultat | Fiches et quiz dans l'app | Fiches, quiz, exercices, **résumés audio** + un carnet par cours dans NotebookLM | Idem |
| Fiabilité | API officielle | Protocole non officiel porté en Rust : peut casser si Google change NotebookLM | Bibliothèque Python non officielle |

Les moteurs se complètent : un cours créé avec NotebookLM garde son carnet, et en cas de
panne réseau l'app bascule sur Gemini si une clé est renseignée. La session Google du moteur
intégré est chiffrée sur le téléphone (Android Keystore).

## Récupérer l'APK

À chaque push, GitHub Actions construit l'application (onglet **Actions → APK Android → artefact
`mescours-apk`**). Les Actions doivent être actives sur le compte GitHub (*Settings → Billing*). Décompressez l'archive, copiez `app-debug.apk` sur le téléphone et ouvrez-la
(autorisez l'installation depuis des sources inconnues).

Les APK debug sont toutes signées avec la même clé (`android/app/debug.keystore`, versionnée) :
chaque nouvelle version s'installe par-dessus la précédente en gardant cours et réglages.
Cette clé étant publique, pour une diffusion au-delà de la famille, signez une release : créez
une clé (`keytool -genkeypair -v -keystore mescours.jks -alias mescours -keyalg RSA -keysize 2048 -validity 10000`)
puis ajoutez dans *Settings → Secrets → Actions* : `KEYSTORE_BASE64` (`base64 -w0 mescours.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. L'artefact contiendra alors `app-release.apk`.

## Construire en local

Prérequis : SDK et NDK Android, Rust (`rustup target add aarch64-linux-android x86_64-linux-android`)
et `cargo install cargo-ndk` — Gradle compile `nlm-core` automatiquement.
Android Studio (Ladybug ou plus récent) : ouvrir le dossier `android/`. Ou en ligne de commande :

```bash
cd android && ./gradlew assembleDebug
# → android/app/build/outputs/apk/debug/app-debug.apk
```

## Premier lancement

1. Ajoutez un enfant (prénom et classe : la classe ajuste le niveau des fiches et des quiz).
2. **Réglages** : choisissez le moteur, puis collez la clé Gemini et/ou, pour NotebookLM,
   **Connecter mon compte Google** (moteur intégré). Pour la passerelle : jeton PocketDock
   (Moteur → API de contrôle → Copier) puis **Installer la passerelle**.
3. **Nouveau cours** → matière → photos → « Créer les fiches par thème ».

## Organisation du code

```
nlm-core/                     Client NotebookLM natif (Rust, UniFFI) : moteur intégré
android/                      App Kotlin + Jetpack Compose
  app/src/main/java/fr/mescours/app/
    engine/                   StudyEngine, GeminiEngine, NotebookLmBridgeEngine, consignes
    data/                     Bibliothèque locale (JSON) et réglages
    ui/screens/               Accueil, capture, matière, fiche, quiz, exercices, réglages
bridge/                       Passerelle Python (FastAPI + notebooklm-py)
.github/workflows/android.yml Build de l'APK
```

## Vie privée

Les cours, fiches et quiz sont stockés uniquement sur le téléphone. Les photos sont envoyées à
Google (Gemini ou NotebookLM) pour analyse. Avec l'offre gratuite de l'API Gemini, Google peut
utiliser les contenus envoyés pour améliorer ses produits ; activez la facturation sur la clé si
vous voulez l'éviter.
