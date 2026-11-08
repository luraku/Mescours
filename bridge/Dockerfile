# Passerelle NotebookLM pour l'app « Mes Cours ».
# Image légère (pas de navigateur) : la connexion Google se fait depuis l'app,
# qui envoie ses cookies à POST /auth/cookies. Compatible amd64 et arm64.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    NOTEBOOKLM_HOME=/data

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY server.py prompts.py ./

# Session Google (storage_state.json) conservée entre les redémarrages.
VOLUME /data
EXPOSE 8765

HEALTHCHECK --interval=60s --timeout=5s --start-period=20s \
  CMD python -c "import urllib.request,sys; urllib.request.urlopen('http://127.0.0.1:8765/ping', timeout=4)" || exit 1

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8765"]
