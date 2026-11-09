//! Client HTTP NotebookLM : session, jetons, appels RPC, upload, chat, médias.

use crate::cookies::Jar;
use crate::error::{NlmError, Result};
use crate::rows;
use crate::tokens::{self, Tokens};
use crate::wire;
use reqwest::{header, redirect, Client, StatusCode};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use url::Url;

pub const DEFAULT_BASE: &str = "https://notebook.google.com";
const DEFAULT_BL: &str = "boq_labs-tailwind-frontend_20260802.02_p0";
const ROTATE_URL: &str = "https://accounts.google.com/RotateCookies";
const PERSONAL_HOSTS: [&str; 2] = ["notebook.google.com", "notebooklm.google.com"];
/// Agent du navigateur intégré de l'app, dont proviennent les cookies.
const USER_AGENT: &str =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";

pub struct Inner {
    pub base: String,
    pub hl: String,
    pub jar: Arc<Jar>,
    http: Client,
    no_redirect: Client,
    media: Client,
    tokens: Mutex<Option<Tokens>>,
    reqid: AtomicU64,
}

fn trusted_media_host(host: &str) -> bool {
    ["google.com", "googleusercontent.com", "googleapis.com"]
        .iter()
        .any(|d| host == *d || host.ends_with(&format!(".{d}")))
}

impl Inner {
    pub fn new(jar: Jar, base: &str, hl: &str) -> Result<Self> {
        let jar = Arc::new(jar);
        let build = |policy: redirect::Policy, timeout: u64| {
            Client::builder()
                .cookie_provider(jar.clone())
                .user_agent(USER_AGENT)
                .redirect(policy)
                .connect_timeout(Duration::from_secs(20))
                .timeout(Duration::from_secs(timeout))
                .build()
        };
        let media_policy = redirect::Policy::custom(|attempt| {
            let ok = attempt.url().scheme() == "https" && attempt.url().host_str().map(trusted_media_host).unwrap_or(false);
            if attempt.previous().len() > 20 || !ok {
                attempt.stop()
            } else {
                attempt.follow()
            }
        });
        Ok(Self {
            base: base.trim_end_matches('/').to_string(),
            hl: if hl.is_empty() { "en".into() } else { hl.into() },
            http: build(redirect::Policy::limited(10), 300)?,
            no_redirect: build(redirect::Policy::none(), 300)?,
            media: build(media_policy, 600)?,
            jar,
            tokens: Mutex::new(None),
            reqid: AtomicU64::new(100_000),
        })
    }

    fn host(&self) -> String {
        Url::parse(&self.base).ok().and_then(|u| u.host_str().map(str::to_string)).unwrap_or_default()
    }

    // ------------------------------------------------------------------ session

    /// Renouvelle `__Secure-1PSIDTS` (best effort, comme le fait Chrome).
    pub async fn rotate_cookies(&self) {
        if !(self.jar.has("OSID") || (self.jar.has("APISID") && self.jar.has("SAPISID"))) {
            return;
        }
        let _ = self
            .http
            .post(ROTATE_URL)
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::ORIGIN, "https://accounts.google.com")
            .body(r#"[000,"-0000000000000000000"]"#)
            .timeout(Duration::from_secs(15))
            .send()
            .await;
    }

    fn classify_final_url(url: &Url) -> Option<NlmError> {
        let host = url.host_str().unwrap_or_default();
        if host == "notebooklm.google" || host == "notebook.google" || host.ends_with(".notebooklm.google") || host.ends_with(".notebook.google") {
            return Some(NlmError::rejected("NotebookLM n'est pas accessible depuis ce réseau (page d'accès régionale)"));
        }
        if host == "accounts.google.com" || host.ends_with(".accounts.google.com") {
            if url.path().trim_matches('/').eq_ignore_ascii_case("cookiemismatch") {
                return Some(NlmError::auth("cookies Google incohérents (CookieMismatch) : reconnectez-vous"));
            }
            return Some(NlmError::auth("déconnecté de Google : reconnectez-vous dans l'app"));
        }
        None
    }

    async fn fetch_tokens(&self) -> Result<Tokens> {
        self.rotate_cookies().await;
        let resp = self.http.get(format!("{}/", self.base)).timeout(Duration::from_secs(30)).send().await?;
        if let Some(e) = Self::classify_final_url(resp.url()) {
            return Err(e);
        }
        let resp = resp.error_for_status()?;
        let html = resp.text().await?;
        if let Some(t) = tokens::extract(&html) {
            return Ok(t);
        }
        // Depuis oct. 2026, une session déconnectée reçoit un 200 sans jeton : on sonde /login.
        let probe = self.http.get(format!("{}/login", self.base)).timeout(Duration::from_secs(30)).send().await?;
        if let Some(e) = Self::classify_final_url(probe.url()) {
            return Err(e);
        }
        Err(NlmError::protocol("jeton de session introuvable (page NotebookLM modifiée ?)"))
    }

    pub async fn tokens(&self, refresh: bool) -> Result<Tokens> {
        let mut guard = self.tokens.lock().await;
        if refresh || guard.is_none() {
            *guard = Some(self.fetch_tokens().await?);
        }
        Ok(guard.clone().unwrap())
    }

    // ------------------------------------------------------------------ RPC

    /// Appel batchexecute. `replay_safe=false` pour les créations (jamais rejouées
    /// après une erreur serveur, le serveur ayant pu déjà les enregistrer).
    pub async fn rpc(&self, rpc_id: &str, params: Value, source_path: &str, replay_safe: bool) -> Result<Value> {
        let mut refreshed = false;
        let mut attempt = 0u32;
        loop {
            let t = self.tokens(false).await?;
            let url = format!(
                "{}/_/LabsTailwindUi/data/batchexecute?rpcids={}&source-path={}&f.sid={}&hl={}&rt=c",
                self.base,
                wire::query_value(rpc_id),
                wire::query_value(source_path),
                wire::query_value(&t.session_id),
                wire::query_value(&self.hl),
            );
            let resp = self
                .http
                .post(&url)
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded;charset=UTF-8")
                .body(wire::batch_body(rpc_id, &params, &t.csrf))
                .send()
                .await;
            let resp = match resp {
                Ok(r) => r,
                Err(e) if replay_safe && attempt < 3 && (e.is_timeout() || e.is_connect()) => {
                    attempt += 1;
                    tokio::time::sleep(backoff(attempt)).await;
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            let status = resp.status();
            if matches!(status, StatusCode::BAD_REQUEST | StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN) {
                // Jeton CSRF périmé : on rafraîchit une seule fois (la requête a été refusée, donc rejouable).
                if !refreshed {
                    refreshed = true;
                    self.tokens(true).await?;
                    continue;
                }
                return Err(NlmError::auth(format!("{rpc_id} refusé (HTTP {status})")));
            }
            if status == StatusCode::TOO_MANY_REQUESTS {
                if replay_safe && attempt < 3 {
                    attempt += 1;
                    tokio::time::sleep(retry_after(&resp).unwrap_or_else(|| backoff(attempt))).await;
                    continue;
                }
                return Err(NlmError::rate(format!("{rpc_id} : trop de requêtes")));
            }
            if status.is_server_error() {
                if replay_safe && attempt < 3 {
                    attempt += 1;
                    tokio::time::sleep(backoff(attempt)).await;
                    continue;
                }
                return Err(NlmError::Network { message: format!("{rpc_id} : erreur serveur HTTP {status}") });
            }
            let body = resp.error_for_status()?.text().await?;
            return match wire::rpc_result(&body, rpc_id) {
                Err(NlmError::AuthExpired { .. }) if !refreshed && replay_safe => {
                    refreshed = true;
                    self.tokens(true).await?;
                    continue;
                }
                other => other,
            };
        }
    }

    fn nb_path(notebook_id: &str) -> String {
        format!("/notebook/{notebook_id}")
    }

    pub async fn list_notebooks(&self) -> Result<Value> {
        self.rpc("wXbhsf", json!([null, 1, null, [2]]), "/", true).await
    }

    pub async fn create_notebook(&self, title: &str) -> Result<String> {
        let r = self.rpc("CCqFvf", json!([title, null, null, rows::template()]), "/", false).await?;
        rows::created_notebook_id(&r)
    }

    pub async fn sources(&self, notebook_id: &str) -> Result<Vec<rows::SourceState>> {
        let r = self
            .rpc("rLM1Ne", json!([notebook_id, null, rows::template(), null, 0]), &Self::nb_path(notebook_id), true)
            .await?;
        rows::notebook_sources(&r)
    }

    // ------------------------------------------------------------------ upload

    /// Ajoute un fichier (photo, PDF…) comme source : enregistrement, session Scotty, envoi.
    pub async fn add_file_source(&self, notebook_id: &str, filename: &str, mime: &str, data: Vec<u8>) -> Result<String> {
        let r = self
            .rpc("o4cbdc", json!([[[filename]], notebook_id, rows::template()]), &Self::nb_path(notebook_id), false)
            .await?;
        let source_id = rows::registered_source_id(&r, filename)?;

        // Étape B : ouverture de la session d'envoi « resumable ».
        let origin = {
            let u = Url::parse(&self.base).map_err(|_| NlmError::protocol("URL de base invalide"))?;
            match u.port() {
                Some(p) => format!("{}://{}:{p}", u.scheme(), self.host()),
                None => format!("{}://{}", u.scheme(), self.host()),
            }
        };
        let start_body = format!(
            "{{\"PROJECT_ID\": {}, \"SOURCE_NAME\": {}, \"SOURCE_ID\": {}}}",
            wire::py_json(&json!(notebook_id)),
            wire::py_json(&json!(filename)),
            wire::py_json(&json!(source_id)),
        );
        let start = self
            .no_redirect
            .post(format!("{}/upload/_/?authuser=0", self.base))
            .header(header::ACCEPT, "*/*")
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded;charset=UTF-8")
            .header(header::ORIGIN, &origin)
            .header(header::REFERER, format!("{origin}/"))
            .header("x-goog-authuser", "0")
            .header("x-goog-upload-command", "start")
            .header("x-goog-upload-header-content-length", data.len().to_string())
            .header("x-goog-upload-header-content-type", mime)
            .header("x-goog-upload-protocol", "resumable")
            .body(start_body)
            .send()
            .await?;
        upload_status_error(start.status())?;
        let upload_url = start
            .headers()
            .get("x-goog-upload-url")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string)
            .ok_or_else(|| NlmError::protocol("URL d'envoi absente (X-Goog-Upload-URL)"))?;
        let parsed = Url::parse(&upload_url).map_err(|_| NlmError::protocol("URL d'envoi invalide"))?;
        let up_host = parsed.host_str().unwrap_or_default().to_string();
        let base_scheme = Url::parse(&self.base).map(|u| u.scheme().to_string()).unwrap_or_default();
        if parsed.scheme() != base_scheme
            || !(PERSONAL_HOSTS.contains(&up_host.as_str()) || up_host == self.host())
            || parsed.path() != "/upload/_/"
        {
            return Err(NlmError::protocol(format!("URL d'envoi inattendue : {up_host}")));
        }

        // Étape C : envoi du contenu et finalisation ; Origin = hôte de l'URL d'envoi.
        let up_origin = match parsed.port() {
            Some(p) => format!("{}://{up_host}:{p}", parsed.scheme()),
            None => format!("{}://{up_host}", parsed.scheme()),
        };
        let done = self
            .no_redirect
            .post(&upload_url)
            .header(header::ACCEPT, "*/*")
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded;charset=utf-8")
            .header("x-goog-authuser", "0")
            .header(header::ORIGIN, &up_origin)
            .header(header::REFERER, format!("{up_origin}/"))
            .header("x-goog-upload-command", "upload, finalize")
            .header("x-goog-upload-offset", "0")
            .body(data)
            .send()
            .await?;
        upload_status_error(done.status())?;
        Ok(source_id)
    }

    /// Attend que les sources soient prêtes (statut 2) ; échec si l'une passe en erreur (3).
    pub async fn wait_sources_ready(&self, notebook_id: &str, ids: &[String], timeout: Duration) -> Result<()> {
        let deadline = tokio::time::Instant::now() + timeout;
        let mut interval = Duration::from_millis(1000);
        loop {
            let states = self.sources(notebook_id).await?;
            let mut pending = false;
            for id in ids {
                match states.iter().find(|s| &s.id == id) {
                    None => return Err(NlmError::not_found(format!("source {id} absente du carnet"))),
                    Some(s) if s.status == 3 => {
                        return Err(NlmError::rejected(format!("NotebookLM n'a pas pu lire « {} »", s.title)))
                    }
                    Some(s) if s.status != 2 => pending = true,
                    _ => {}
                }
            }
            if !pending {
                return Ok(());
            }
            if tokio::time::Instant::now() >= deadline {
                return Err(NlmError::Network { message: "les sources ne sont pas prêtes à temps".into() });
            }
            tokio::time::sleep(interval).await;
            interval = (interval.mul_f32(1.5)).min(Duration::from_secs(10));
        }
    }

    pub async fn source_text(&self, notebook_id: &str, source_id: &str) -> Result<String> {
        let r = self.rpc("hizoJc", json!([[source_id], [2], [2]]), &Self::nb_path(notebook_id), true).await?;
        if r.is_null() {
            return Err(NlmError::not_found("source introuvable"));
        }
        Ok(rows::source_text(&r))
    }

    // ------------------------------------------------------------------ réglages

    pub async fn output_language(&self) -> Result<Option<String>> {
        let r = self
            .rpc("ZwVcOc", json!([null, [1, null, null, null, null, null, null, null, null, null, [1]]]), "/", true)
            .await?;
        Ok(rows::output_language(&r))
    }

    pub async fn set_output_language(&self, language: &str) -> Result<()> {
        if language.is_empty() {
            return Ok(());
        }
        self.rpc("hT54vc", json!([[[null, [[null, null, null, null, [language]]]]]]), "/", true).await?;
        Ok(())
    }

    // ------------------------------------------------------------------ chat

    pub async fn ask(&self, notebook_id: &str, question: &str) -> Result<String> {
        let sources: Vec<Value> = self.sources(notebook_id).await?.into_iter().map(|s| json!([[s.id]])).collect();
        let params = json!([sources, question, null, [2, null, [1], [1]], null, null, null, notebook_id, 1]);
        let mut refreshed = false;
        loop {
            let t = self.tokens(false).await?;
            let reqid = self.reqid.fetch_add(100_000, Ordering::SeqCst) + 100_000;
            let bl = t.build_label.clone().unwrap_or_else(|| DEFAULT_BL.into());
            let mut url = format!(
                "{}/_/LabsTailwindUi/data/google.internal.labs.tailwind.orchestration.v1.LabsTailwindOrchestrationService/GenerateFreeFormStreamed?bl={}&hl={}&_reqid={}&rt=c",
                self.base,
                wire::query_value(&bl),
                wire::query_value(&self.hl),
                reqid
            );
            if !t.session_id.is_empty() {
                url.push_str(&format!("&f.sid={}", wire::query_value(&t.session_id)));
            }
            let resp = self
                .http
                .post(&url)
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded;charset=UTF-8")
                .body(wire::chat_body(&params, &t.csrf))
                .timeout(Duration::from_secs(300))
                .send()
                .await?;
            let status = resp.status();
            if matches!(status, StatusCode::BAD_REQUEST | StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN) && !refreshed {
                refreshed = true;
                self.tokens(true).await?;
                continue;
            }
            if status == StatusCode::TOO_MANY_REQUESTS {
                return Err(NlmError::rate("chat : trop de requêtes"));
            }
            let body = resp.error_for_status()?.text().await?;
            return wire::chat_answer(&body);
        }
    }

    // ------------------------------------------------------------------ artefacts

    async fn all_source_triples(&self, notebook_id: &str) -> Result<Vec<String>> {
        let ids: Vec<String> = self.sources(notebook_id).await?.into_iter().map(|s| s.id).collect();
        if ids.is_empty() {
            return Err(NlmError::rejected("le carnet ne contient aucune source"));
        }
        Ok(ids)
    }

    pub async fn create_quiz(&self, notebook_id: &str, instructions: Option<&str>, quantity: u8, difficulty: u8) -> Result<String> {
        let ids = self.all_source_triples(notebook_id).await?;
        let triple: Vec<Value> = ids.iter().map(|i| json!([[i]])).collect();
        let params = json!([
            rows::artifact_options(),
            notebook_id,
            [null, null, 4, triple, null, null, null, null, null,
             [null, [2, null, instructions, null, null, null, null, [quantity.clamp(1, 3), difficulty.clamp(1, 3)]]]]
        ]);
        let r = self.rpc("R7cb6c", params, &Self::nb_path(notebook_id), false).await?;
        rows::created_artifact_id(&r)
    }

    /// Résumé audio : format 2 = bref (court), 1 = approfondi ; durée 1 = courte, 2 = normale.
    pub async fn create_audio(&self, notebook_id: &str, instructions: Option<&str>, language: &str, short: bool) -> Result<String> {
        let ids = self.all_source_triples(notebook_id).await?;
        let triple: Vec<Value> = ids.iter().map(|i| json!([[i]])).collect();
        let double: Vec<Value> = ids.iter().map(|i| json!([i])).collect();
        let (length, format) = if short { (1, 2) } else { (2, 1) };
        let params = json!([
            rows::artifact_options(),
            notebook_id,
            [null, null, 1, triple, null, null, [null, [instructions, length, null, double, language, null, format]]]
        ]);
        let r = self.rpc("R7cb6c", params, &Self::nb_path(notebook_id), false).await?;
        rows::created_artifact_id(&r)
    }

    pub async fn artifact(&self, notebook_id: &str, artifact_id: &str) -> Result<rows::ArtifactRow> {
        let r = self
            .rpc(
                "gArtLc",
                json!([[2], notebook_id, "NOT artifact.status = \"ARTIFACT_STATUS_SUGGESTED\""]),
                &Self::nb_path(notebook_id),
                true,
            )
            .await?;
        Ok(rows::artifact_row(&r, artifact_id))
    }

    pub async fn quiz_json(&self, notebook_id: &str, artifact_id: &str) -> Result<String> {
        let r = self.rpc("v9rmvd", json!([artifact_id]), &Self::nb_path(notebook_id), true).await?;
        rows::quiz_json_from_result(&r)
    }

    /// Télécharge un média (audio) avec les cookies Google, redirections limitées aux hôtes Google.
    pub async fn download_media(&self, url: &str, dest: &std::path::Path) -> Result<u64> {
        use futures_util::StreamExt;
        use tokio::io::AsyncWriteExt;
        let parsed = Url::parse(url).map_err(|_| NlmError::protocol("URL de média invalide"))?;
        if parsed.scheme() != "https" || !parsed.host_str().map(trusted_media_host).unwrap_or(false) {
            return Err(NlmError::protocol("URL de média hors des domaines Google"));
        }
        let resp = self.media.get(url).send().await?;
        if matches!(resp.status(), StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN) {
            return Err(NlmError::auth("téléchargement du média refusé"));
        }
        let resp = resp.error_for_status()?;
        let ctype = resp.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or_default();
        if ctype.contains("text/html") {
            return Err(NlmError::auth("page de connexion reçue au lieu du média"));
        }
        let tmp = dest.with_extension("part");
        let mut file = tokio::fs::File::create(&tmp).await?;
        let mut total = 0u64;
        let mut stream = resp.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk?;
            total += chunk.len() as u64;
            file.write_all(&chunk).await?;
        }
        file.flush().await?;
        drop(file);
        if total == 0 {
            let _ = tokio::fs::remove_file(&tmp).await;
            return Err(NlmError::not_found("média vide"));
        }
        tokio::fs::rename(&tmp, dest).await?;
        Ok(total)
    }
}

fn upload_status_error(status: StatusCode) -> Result<()> {
    match status.as_u16() {
        200..=299 => Ok(()),
        401 | 403 | 300..=399 => Err(NlmError::auth(format!("envoi refusé (HTTP {status})"))),
        429 => Err(NlmError::rate("envoi : trop de requêtes")),
        500..=599 => Err(NlmError::Network { message: format!("envoi : erreur serveur {status}") }),
        _ => Err(NlmError::rejected(format!("type de fichier refusé (HTTP {status})"))),
    }
}

fn backoff(attempt: u32) -> Duration {
    let base = (1u64 << attempt.min(5)).min(30) as f64;
    let jitter = 1.0 + (rand::random::<f64>() - 0.5) * 0.4;
    Duration::from_secs_f64((base * jitter).max(0.1))
}

fn retry_after(resp: &reqwest::Response) -> Option<Duration> {
    resp.headers()
        .get(header::RETRY_AFTER)?
        .to_str()
        .ok()?
        .trim()
        .parse::<f64>()
        .ok()
        .map(|s| Duration::from_secs_f64(s.clamp(0.0, 300.0)))
}
