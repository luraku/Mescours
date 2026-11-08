# Passerelle NotebookLM

Petit serveur à faire tourner sur un PC (ou NAS, Raspberry Pi…) connecté à **votre** compte
Google. L'app Android lui envoie les photos ; il crée un carnet NotebookLM par cours, en tire
les fiches par thème, puis génère les quiz (Studio NotebookLM) et les exercices (chat).

> ⚠️ NotebookLM n'a pas d'API officielle pour les comptes personnels. La passerelle s'appuie
> sur [`notebooklm-py`](https://github.com/teng-lin/notebooklm-py), une bibliothèque **non
> officielle** qui reproduit les appels du site. Elle peut cesser de fonctionner si Google
> modifie NotebookLM, et un usage intensif peut être limité. En cas de panne, l'app bascule
> sur Gemini si une clé est renseignée.

## Installation

```bash
cd bridge
python3 -m venv .venv && source .venv/bin/activate     # Windows : .venv\Scripts\activate
pip install -r requirements.txt
playwright install chromium        # navigateur utilisé une seule fois pour la connexion
notebooklm login                   # ouvre une fenêtre : connectez-vous à votre compte Google
notebooklm auth check --test       # vérifie que la session fonctionne
```

## Lancement

```bash
export BRIDGE_TOKEN="choisissez-un-long-secret"         # Windows : set BRIDGE_TOKEN=...
uvicorn server:app --host 0.0.0.0 --port 8765
```

Dans l'app : **Réglages → Passerelle NotebookLM**
- Adresse : `http://<IP-du-PC>:8765` (ex. `http://192.168.1.20:8765`, trouvez l'IP avec `ip a` / `ipconfig`)
- Jeton : la valeur de `BRIDGE_TOKEN`
- **Tester la passerelle**, puis choisissez le moteur « NotebookLM ».

Le téléphone doit être sur le même Wi-Fi que le PC. Pour l'utiliser hors de la maison,
installez [Tailscale](https://tailscale.com) sur le PC et le téléphone et utilisez l'adresse
Tailscale du PC. N'exposez pas directement ce port sur Internet.

## Options (variables d'environnement)

| Variable | Rôle | Défaut |
|---|---|---|
| `BRIDGE_TOKEN` | Jeton exigé par la passerelle (`Authorization: Bearer …`) | aucun (déconseillé) |
| `NOTEBOOKLM_PROFILE` | Profil `notebooklm-py` à utiliser (plusieurs comptes Google) | profil actif |
| `MESCOURS_UPLOAD_AS_PDF` | `1` = regroupe les photos dans un seul PDF au lieu d'une source par photo | `0` |
| `MESCOURS_GENERATION_TIMEOUT` | Attente max. de la génération d'un quiz (secondes) | `420` |

La session Google expire de temps en temps : relancez `notebooklm login`, ou planifiez
`notebooklm auth refresh --quiet` (cron / tâche planifiée).

## API

| Méthode | Route | Corps | Réponse |
|---|---|---|---|
| GET | `/health` | – | `{"status"}` |
| POST | `/courses` | multipart : `subject`, `grade`, `files[]` (JPEG) | `{"title","transcript","themes","notebookId"}` |
| POST | `/courses/{notebookId}/quiz` | `{"subject","grade","theme","count"}` | `{"questions":[{"question","options","correctIndex","explanation","hint"}]}` |
| POST | `/courses/{notebookId}/exercises` | idem | `{"exercises":[{"statement","hint","solution","difficulty"}]}` |
