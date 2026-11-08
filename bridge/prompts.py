"""Consignes envoyées au chat NotebookLM (mêmes formats JSON que le moteur Gemini de l'app)."""

_JSON_ONLY = (
    "Réponds UNIQUEMENT avec un objet JSON valide, sans texte avant ou après, "
    "sans balise de code et sans numéros de citation."
)


def structure(subject: str, grade: str) -> str:
    return f"""Tu es un professeur bienveillant qui prépare des fiches de révision pour un élève de {grade} en {subject}.
Les sources de ce carnet sont des photos du cours (cahier ou manuel).
Découpe le cours en thèmes cohérents (2 à 6 en général). Pour chaque thème donne :
"title" (titre clair), "summary" (résumé de 3 à 6 phrases adapté au niveau {grade}),
"keyPoints" (liste des points essentiels), "vocabulary" (liste d'objets {{"term", "definition"}}),
"examples" (exemples tirés du cours). Reste strictement fidèle au cours, en français.
{_JSON_ONLY}
Format : {{"title": "titre court du cours", "themes": [{{"title": "", "summary": "", "keyPoints": [], "vocabulary": [{{"term": "", "definition": ""}}], "examples": []}}]}}"""


def quiz_instructions(subject: str, grade: str, theme: str) -> str:
    return (
        f"Quiz en français pour un élève de {grade} en {subject}, "
        f"uniquement sur le thème « {theme} ». Questions variées et adaptées à son niveau."
    )


def quiz_json(subject: str, grade: str, theme: str, count: int) -> str:
    return f"""Crée un quiz de {count} questions à choix multiples (4 propositions, une seule bonne réponse)
pour un élève de {grade} en {subject}, uniquement sur le thème « {theme} » de ce cours.
{_JSON_ONLY}
Format : {{"questions": [{{"question": "", "options": ["", "", "", ""], "correctIndex": 0, "explanation": "", "hint": ""}}]}}"""


def exercises_json(subject: str, grade: str, theme: str, count: int) -> str:
    return f"""Crée {count} exercices progressifs (du plus facile au plus difficile) pour un élève de {grade} en {subject},
uniquement sur le thème « {theme} » de ce cours, avec une correction détaillée étape par étape.
{_JSON_ONLY}
Format : {{"exercises": [{{"statement": "", "hint": "", "solution": "", "difficulty": "facile|moyen|difficile"}}]}}"""
