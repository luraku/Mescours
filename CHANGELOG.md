# Journal des versions

## v0.1.0 — première version (11/11/2026)

Application Android pour réviser les cours des enfants, dans l'esprit de Knowunity.

### Fonctionnalités
- **Cours en photo** : photographier le cahier ou importer des photos de la galerie (jusqu'à 20 pages) ;
  l'IA découpe le cours en **fiches par thème** (résumé, points clés, vocabulaire, exemples),
  rangées par enfant et par matière.
- **Quiz** QCM (indice, explication, score, propositions mélangées) et **exercices** progressifs corrigés.
- **Résumé audio** NotebookLM par thème (court ou détaillé), avec lecteur intégré.
- **Écran « Cours »** : photos d'origine (plein écran, zoom), résumés audio et fiches de chaque cours.
- **Maths lisibles** : notation LaTeX convertie, fractions empilées.
- **Gamification** : XP, 10 niveaux, série de jours 🔥, 16 badges, écran « Succès », récompenses.
- **Trois moteurs IA** :
  - Gemini (API officielle, clé gratuite) ;
  - **NotebookLM intégré** (expérimental) : client natif Rust `nlm-core`, sans passerelle,
    session Google chiffrée sur le téléphone ;
  - NotebookLM via la passerelle Python (PocketDock ou PC), installable et relancée depuis l'app.

### Installation
Télécharger `MesCours-v0.1.0.apk` ci-dessous, l'ouvrir sur le téléphone (autoriser les sources
inconnues). Elle s'installe par-dessus les APK de test précédentes (même signature).

### Limites connues
- Le moteur NotebookLM intégré et la passerelle reposent sur des protocoles **non officiels** :
  ils peuvent cesser de fonctionner si Google modifie NotebookLM.
- Lecture audio écran éteint et notification « résumé prêt » app fermée : pas encore (#13).
- Validation sur téléphone en cours pour plusieurs évolutions (#10 à #14).
