# Passerelle NotebookLM

Petit serveur, en conteneur Docker (sur le téléphone ou un serveur) ou sur un PC, qui pilote
**votre** compte NotebookLM. L'app Android lui envoie les photos ; il crée un carnet NotebookLM par cours, en tire
les fiches par thème, puis génère les quiz (Studio NotebookLM) et les exercices (chat).

> ⚠️ NotebookLM n'a pas d'API officielle pour les comptes personnels. La passerelle s'appuie
> sur [`notebooklm-py`](https://github.com/teng-lin/notebooklm-py), une bibliothèque **non
> officielle** qui reproduit les appels du site. Elle peut cesser de fonctionner si Google
> modifie NotebookLM, et un usage intensif peut être limité. En cas de panne, l'app bascule
> sur Gemini si une clé est renseignée.

## Option A : conteneur Docker (sur le téléphone ou un serveur)

L'image ne contient pas de navigateur : **la connexion Google se fait dans l'app**, qui
transmet ensuite la session au conteneur.

```bash
cd bridge
BRIDGE_TOKEN="choisissez-un-long-secret" docker compose up -d --build
# ou sans compose :
docker build -t mescours-bridge .
docker run -d --name mescours-bridge --restart unless-stopped \
  -p 8765:8765 -e BRIDGE_TOKEN="choisissez-un-long-secret" \
  -v "$PWD/data:/data" mescours-bridge
```

La session Google est conservée dans `./data` (volume `/data`), donc elle survit aux redémarrages.

### Sur le téléphone avec PocketDock : installation depuis l'app

L'app pilote PocketDock par son API de contrôle (`http://127.0.0.1:7777/v1/`) :
1. Dans PocketDock : onglet **Moteur → « API de contrôle » → Copier** (en APK release,
   activez d'abord l'interrupteur de l'API).
2. Dans Mes Cours : **Réglages → PocketDock**, collez le jeton, **Tester**, puis
   **Installer la passerelle**.

L'app démarre le moteur si besoin, envoie la stack Compose `mescours-bridge` (sources de la
passerelle embarquées dans l'APK, avec un `BRIDGE_TOKEN` généré automatiquement), la lance,
suit la construction et règle l'adresse `http://127.0.0.1:8765`. La première construction
télécharge Python et les dépendances : comptez quelques minutes.

Ensuite, si la passerelle ne répond plus (téléphone redémarré, moteur arrêté…), l'app relance
automatiquement le moteur et la stack avant de basculer sur Gemini.

### Sur un autre moteur Docker du téléphone

Créez le conteneur depuis ce dossier en publiant le port `8765`, la variable `BRIDGE_TOKEN` et
un volume pour `/data`, puis utilisez l'adresse `http://127.0.0.1:8765` dans l'app.

### Connecter votre compte

Dans l'app : **Réglages → Passerelle NotebookLM**
1. Adresse (`http://127.0.0.1:8765` en local) et jeton (`BRIDGE_TOKEN`), puis **Tester**.
2. **Connecter mon compte Google** : connectez-vous à NotebookLM dans la page qui s'ouvre,
   puis **Envoyer la connexion à la passerelle**.
3. Choisissez le moteur « NotebookLM » et **Enregistrez**.

Si Google refuse la connexion dans l'app (« navigateur non sécurisé »), exportez les cookies de
notebooklm.google.com depuis un navigateur (ex. extension Cookie-Editor, export JSON) et
envoyez-les au conteneur :

```bash
curl -X POST http://127.0.0.1:8765/auth/cookies \
  -H "Authorization: Bearer $BRIDGE_TOKEN" -H "Content-Type: application/json" \
  --data @cookies.json
```

## Option B : directement sur un PC

```bash
cd bridge
python3 -m venv .venv && source .venv/bin/activate     # Windows : .venv\Scripts\activate
pip install -r requirements.txt "notebooklm-py[browser]==0.8.6"
playwright install chromium
notebooklm login                   # ou : la connexion depuis l'app, comme en Docker
export BRIDGE_TOKEN="choisissez-un-long-secret"
uvicorn server:app --host 0.0.0.0 --port 8765
```

Dans l'app, l'adresse est `http://<IP-du-PC>:8765` (même Wi-Fi, ou Tailscale hors de la
maison). N'exposez pas ce port directement sur Internet.

## Options (variables d'environnement)

| Variable | Rôle | Défaut |
|---|---|---|
| `BRIDGE_TOKEN` | Jeton exigé par la passerelle (`Authorization: Bearer …`) | aucun (déconseillé) |
| `NOTEBOOKLM_PROFILE` | Profil `notebooklm-py` à utiliser (plusieurs comptes Google) | profil actif |
| `MESCOURS_UPLOAD_AS_PDF` | `1` = regroupe les photos dans un seul PDF au lieu d'une source par photo | `0` |
| `MESCOURS_GENERATION_TIMEOUT` | Attente max. de la génération d'un quiz (secondes) | `420` |

La session Google expire de temps en temps : refaites « Connecter mon compte Google » dans l'app.

## API

| Méthode | Route | Corps | Réponse |
|---|---|---|---|
| GET | `/ping` | – (sans jeton) | `{"status":"ok"}` |
| GET | `/health` | – | `{"status"}` (vérifie la session NotebookLM) |
| POST | `/auth/cookies` | storage_state Playwright `{"cookies":[…]}` ou liste de cookies | `{"status"}` |
| POST | `/courses` | multipart : `subject`, `grade`, `files[]` (JPEG) | `{"title","transcript","themes","notebookId"}` |
| POST | `/courses/{notebookId}/quiz` | `{"subject","grade","theme","count"}` | `{"questions":[{"question","options","correctIndex","explanation","hint"}]}` |
| POST | `/courses/{notebookId}/exercises` | idem | `{"exercises":[{"statement","hint","solution","difficulty"}]}` |
