"""Passerelle entre l'app Android « Mes Cours » et votre compte NotebookLM.

NotebookLM n'a pas d'API publique pour les comptes personnels : ce serveur utilise
la bibliothèque non officielle ``notebooklm-py``, qui reprend les appels du site web
avec les cookies de votre session Google (``notebooklm login``).

Un cours = un carnet NotebookLM « Mes Cours · <matière> · <date> » contenant les photos.

Lancement :
    export BRIDGE_TOKEN="un-secret-long"
    uvicorn server:app --host 0.0.0.0 --port 8765
"""

from __future__ import annotations

import json
import logging
import os
import re
import secrets
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from notebooklm import NotebookLMClient
from notebooklm.types import QuizDifficulty, QuizQuantity

import prompts

log = logging.getLogger("mescours.bridge")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

BRIDGE_TOKEN = os.environ.get("BRIDGE_TOKEN", "")
# Profil notebooklm-py à utiliser (``notebooklm profile list``) ; vide = profil actif.
NOTEBOOKLM_PROFILE = os.environ.get("NOTEBOOKLM_PROFILE") or None
# 1 = regrouper les photos dans un seul PDF au lieu d'une source par image.
UPLOAD_AS_PDF = os.environ.get("MESCOURS_UPLOAD_AS_PDF", "0") == "1"
GENERATION_TIMEOUT = float(os.environ.get("MESCOURS_GENERATION_TIMEOUT", "420"))
MAX_PAGES = 20
DEBUG = os.environ.get("MESCOURS_DEBUG", "0") == "1"

app = FastAPI(title="Mes Cours – passerelle NotebookLM", version="0.1.0")


@app.exception_handler(Exception)
async def notebooklm_error(_request: Request, exc: Exception) -> JSONResponse:
    """Renvoie à l'app un message lisible plutôt qu'une erreur 500 muette."""
    text = " ".join((str(exc) or exc.__class__.__name__).split())
    # Une ligne lisible dans les journaux (PocketDock) ; trace complète seulement en mode debug.
    log.error("%s %s → %s : %s", _request.method, _request.url.path, exc.__class__.__name__, text)
    if DEBUG:
        log.exception("Trace complète")
    if "auth" in exc.__class__.__name__.lower() or "storage" in text.lower() or "cookie" in text.lower():
        detail = f"Session NotebookLM absente ou expirée : dans l'app, Réglages → « Connecter mon compte Google ». ({text})"
    else:
        detail = f"NotebookLM : {text}"
    return JSONResponse(status_code=502, content={"detail": detail[:500]})


def require_token(authorization: str = Header(default="")) -> None:
    if not BRIDGE_TOKEN:
        return  # pas de jeton configuré : réseau local de confiance uniquement
    expected = f"Bearer {BRIDGE_TOKEN}"
    if not secrets.compare_digest(authorization.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Jeton invalide (BRIDGE_TOKEN).")


def client() -> NotebookLMClient:
    return NotebookLMClient.from_storage(profile=NOTEBOOKLM_PROFILE)


# ---------------------------------------------------------------- utilitaires

_CITATION = re.compile(r"\s?\[\d+(?:\s*[-–,]\s*\d+)*\]")


def parse_json_answer(text: str) -> dict[str, Any]:
    """Extrait l'objet JSON d'une réponse de chat NotebookLM (balises, citations [1]…)."""
    cleaned = _CITATION.sub("", text)
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        raise HTTPException(status_code=502, detail="NotebookLM n'a pas renvoyé de JSON exploitable.")
    try:
        return json.loads(cleaned[start : end + 1])
    except json.JSONDecodeError as e:
        log.warning("JSON invalide reçu de NotebookLM : %s", cleaned[:500])
        raise HTTPException(status_code=502, detail=f"JSON NotebookLM invalide : {e}") from e


def images_to_pdf(paths: list[Path], out: Path) -> Path:
    from PIL import Image

    images = [Image.open(p).convert("RGB") for p in paths]
    images[0].save(out, save_all=True, append_images=images[1:])
    return out


def quiz_quantity(count: int) -> QuizQuantity:
    if count <= 5:
        return QuizQuantity.FEWER
    if count <= 10:
        return QuizQuantity.STANDARD
    return QuizQuantity.MORE


def convert_notebooklm_quiz(data: dict[str, Any]) -> list[dict[str, Any]]:
    """Format NotebookLM {question, answerOptions[{text,isCorrect,rationale}], hint} → format de l'app."""
    out = []
    for q in data.get("questions", []):
        options = q.get("answerOptions") or []
        if len(options) < 2:
            continue
        correct = next((i for i, o in enumerate(options) if o.get("isCorrect")), 0)
        out.append(
            {
                "question": q.get("question", ""),
                "options": [o.get("text", "") for o in options],
                "correctIndex": correct,
                "explanation": options[correct].get("rationale", "") or "",
                "hint": q.get("hint", "") or "",
            }
        )
    return out


# ---------------------------------------------------------------- routes


class ThemeRequest(BaseModel):
    subject: str
    grade: str = ""
    theme: str
    count: int = Field(default=10, ge=1, le=30)


@app.post("/auth/cookies")
async def import_cookies(request: Request, _: None = Depends(require_token)) -> dict[str, Any]:
    """Reçoit les cookies Google (format Playwright storage_state ou liste de cookies)
    capturés par l'app après connexion dans sa WebView, et les enregistre pour notebooklm-py.

    Remplace `notebooklm login`, qui a besoin d'un navigateur absent du conteneur."""
    payload = await request.body()
    try:
        json.loads(payload)
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=400, detail=f"JSON de cookies invalide : {e}") from e
    cmd = [sys.executable, "-m", "notebooklm"]
    if NOTEBOOKLM_PROFILE:
        cmd += ["-p", NOTEBOOKLM_PROFILE]
    cmd += ["auth", "import-cookies", "-", "--quiet"]
    proc = subprocess.run(cmd, input=payload, capture_output=True, timeout=60)
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).decode(errors="replace").strip()
        log.error("Import des cookies refusé : %s", " ".join(err.split())[-400:])
        raise HTTPException(status_code=400, detail=f"Cookies refusés : {err[-400:]}")
    log.info("Cookies Google importés (%d octets), vérification auprès de NotebookLM…", len(payload))
    async with client() as nlm:
        notebooks = await nlm.notebooks.list()
    return {"status": f"compte connecté ({len(notebooks)} carnets)"}


@app.get("/ping")
async def ping() -> dict[str, str]:
    """Vivacité du conteneur, sans appel à NotebookLM ni jeton."""
    return {"status": "ok"}


@app.get("/health")
async def health(_: None = Depends(require_token)) -> dict[str, Any]:
    async with client() as nlm:
        notebooks = await nlm.notebooks.list()
    return {"status": f"connecté à NotebookLM ({len(notebooks)} carnets)"}


@app.post("/courses")
async def create_course(
    subject: str = Form(...),
    grade: str = Form(""),
    files: list[UploadFile] = File(...),
    _: None = Depends(require_token),
) -> dict[str, Any]:
    if not files:
        raise HTTPException(status_code=400, detail="Aucune photo reçue.")
    if len(files) > MAX_PAGES:
        raise HTTPException(status_code=400, detail=f"{MAX_PAGES} pages maximum par cours.")

    with tempfile.TemporaryDirectory(prefix="mescours-") as tmp:
        tmpdir = Path(tmp)
        paths: list[Path] = []
        for i, f in enumerate(files, start=1):
            p = tmpdir / f"page-{i:02d}.jpg"
            p.write_bytes(await f.read())
            paths.append(p)
        uploads = [images_to_pdf(paths, tmpdir / "cours.pdf")] if UPLOAD_AS_PDF else paths

        title = f"Mes Cours · {subject} · {datetime.now():%d/%m/%Y %H:%M}"
        async with client() as nlm:
            nb = await nlm.notebooks.create(title)
            log.info("Carnet créé %s (%s), %d fichier(s)", nb.id, title, len(uploads))
            sources = []
            for p in uploads:
                sources.append(await nlm.sources.add_file(nb.id, p, wait=True, wait_timeout=300, title=p.stem))

            transcript_parts = []
            for s in sources:
                try:
                    full = await nlm.sources.get_fulltext(nb.id, s.id)
                    transcript_parts.append(full.content)
                except Exception as e:  # le texte intégral est un bonus, pas un prérequis
                    log.warning("Texte intégral indisponible pour %s : %s", s.id, e)

            answer = await nlm.chat.ask(nb.id, prompts.structure(subject, grade))
            data = parse_json_answer(answer.answer)

    themes = data.get("themes") or []
    if not themes:
        raise HTTPException(status_code=502, detail="NotebookLM n'a trouvé aucun thème.")
    return {
        "title": data.get("title") or subject,
        "transcript": "\n\n".join(transcript_parts),
        "themes": themes,
        "notebookId": nb.id,
    }


@app.post("/courses/{notebook_id}/quiz")
async def create_quiz(notebook_id: str, req: ThemeRequest, _: None = Depends(require_token)) -> dict[str, Any]:
    async with client() as nlm:
        # 1) Le générateur de quiz officiel du Studio NotebookLM.
        try:
            status = await nlm.artifacts.generate_quiz(
                notebook_id,
                instructions=prompts.quiz_instructions(req.subject, req.grade, req.theme),
                quantity=quiz_quantity(req.count),
                difficulty=QuizDifficulty.MEDIUM,
            )
            final = await nlm.artifacts.wait_for_completion(notebook_id, status.task_id, timeout=GENERATION_TIMEOUT)
            if not final.is_complete:
                raise RuntimeError(final.error or f"statut {final.status}")
            with tempfile.TemporaryDirectory(prefix="mescours-") as tmp:
                out = Path(tmp) / "quiz.json"
                await nlm.artifacts.download_quiz(notebook_id, str(out), artifact_id=status.task_id, output_format="json")
                questions = convert_notebooklm_quiz(json.loads(out.read_text(encoding="utf-8")))
            if questions:
                return {"questions": questions}
            log.warning("Quiz Studio vide, repli sur le chat.")
        except HTTPException:
            raise
        except Exception as e:
            log.warning("Quiz Studio indisponible (%s), repli sur le chat.", e)

        # 2) Repli : on demande le quiz au chat du carnet, au format JSON.
        answer = await nlm.chat.ask(notebook_id, prompts.quiz_json(req.subject, req.grade, req.theme, req.count))
    return {"questions": parse_json_answer(answer.answer).get("questions", [])}


@app.post("/courses/{notebook_id}/exercises")
async def create_exercises(notebook_id: str, req: ThemeRequest, _: None = Depends(require_token)) -> dict[str, Any]:
    async with client() as nlm:
        answer = await nlm.chat.ask(notebook_id, prompts.exercises_json(req.subject, req.grade, req.theme, req.count))
    return {"exercises": parse_json_answer(answer.answer).get("exercises", [])}
