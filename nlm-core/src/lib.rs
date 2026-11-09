//! nlm-core : client NotebookLM natif pour l'app Mes Cours.
//!
//! Sous-ensemble porté en Rust de notebooklm-py (MIT, © 2026 Teng Lin) : protocole web
//! `batchexecute` non officiel. Exposé à Kotlin via UniFFI (`NotebookLmClient`).

uniffi::setup_scaffolding!();

pub mod client;
pub mod cookies;
pub mod error;
pub mod rows;
pub mod tokens;
pub mod wire;

use client::Inner;
use error::{NlmError, Result};
use std::sync::Arc;
use std::time::Duration;

/// Résumé du compte : nombre de carnets et langue de sortie.
#[derive(uniffi::Record)]
pub struct AccountSummary {
    pub notebooks: u32,
    pub output_language: Option<String>,
}

/// État d'un artefact (quiz, audio) : `pending`, `completed`, `failed` ou `not_found`.
#[derive(uniffi::Record)]
pub struct ArtifactState {
    pub status: String,
    pub audio_url: Option<String>,
}

#[derive(uniffi::Record)]
pub struct SourceInfo {
    pub id: String,
    pub title: String,
    /// 1 traitement, 2 prête, 3 erreur, 5 préparation.
    pub status: i64,
}

/// Client NotebookLM lié à une session Google (cookies `storage_state`).
#[derive(uniffi::Object)]
pub struct NotebookLmClient {
    inner: Arc<Inner>,
}

#[uniffi::export(async_runtime = "tokio")]
impl NotebookLmClient {
    /// `session_json` : cookies au format Playwright `storage_state` ; `language` : ex. `fr`.
    #[uniffi::constructor]
    pub fn new(session_json: String, language: String) -> Result<Arc<Self>> {
        Self::with_base(session_json, language, client::DEFAULT_BASE.to_string())
    }

    /// Variante avec hôte explicite (tests, hôte historique notebooklm.google.com).
    #[uniffi::constructor]
    pub fn with_base(session_json: String, language: String, base_url: String) -> Result<Arc<Self>> {
        let jar = cookies::Jar::from_json(&session_json)?;
        Ok(Arc::new(Self { inner: Arc::new(Inner::new(jar, &base_url, &language)?) }))
    }

    /// Session à jour (cookies renouvelés) à ré-enregistrer côté app.
    pub fn export_session(&self) -> String {
        self.inner.jar.to_json()
    }

    pub async fn account_summary(&self) -> Result<AccountSummary> {
        let list = self.inner.list_notebooks().await?;
        let lang = self.inner.output_language().await.ok().flatten();
        Ok(AccountSummary { notebooks: rows::notebook_count(&list), output_language: lang })
    }

    /// Règle la langue de sortie du compte si elle diffère (le générateur de quiz la suit).
    pub async fn ensure_output_language(&self, language: String) -> Result<()> {
        let current = self.inner.output_language().await?;
        if current.as_deref().map(|c| c.eq_ignore_ascii_case(&language)) != Some(true) {
            self.inner.set_output_language(&language).await?;
        }
        Ok(())
    }

    pub async fn create_notebook(&self, title: String) -> Result<String> {
        self.inner.create_notebook(&title).await
    }

    /// Ajoute un fichier local comme source ; renvoie l'id de la source.
    pub async fn add_file_source(&self, notebook_id: String, path: String, mime_type: String) -> Result<String> {
        let data = tokio::fs::read(&path).await?;
        let filename = std::path::Path::new(&path)
            .file_name()
            .and_then(|f| f.to_str())
            .unwrap_or("page.jpg")
            .to_string();
        self.inner.add_file_source(&notebook_id, &filename, &mime_type, data).await
    }

    pub async fn sources(&self, notebook_id: String) -> Result<Vec<SourceInfo>> {
        Ok(self
            .inner
            .sources(&notebook_id)
            .await?
            .into_iter()
            .map(|s| SourceInfo { id: s.id, title: s.title, status: s.status })
            .collect())
    }

    pub async fn wait_sources_ready(&self, notebook_id: String, source_ids: Vec<String>, timeout_secs: u32) -> Result<()> {
        self.inner.wait_sources_ready(&notebook_id, &source_ids, Duration::from_secs(timeout_secs as u64)).await
    }

    pub async fn source_text(&self, notebook_id: String, source_id: String) -> Result<String> {
        self.inner.source_text(&notebook_id, &source_id).await
    }

    /// Pose une question au carnet (toutes ses sources) ; renvoie la réponse finale.
    pub async fn ask(&self, notebook_id: String, question: String) -> Result<String> {
        self.inner.ask(&notebook_id, &question).await
    }

    /// quantity : 1 moins, 2 standard, 3 plus ; difficulty : 1 facile, 2 moyen, 3 difficile.
    pub async fn create_quiz(&self, notebook_id: String, instructions: Option<String>, quantity: u8, difficulty: u8) -> Result<String> {
        self.inner.create_quiz(&notebook_id, instructions.as_deref(), quantity, difficulty).await
    }

    pub async fn create_audio(&self, notebook_id: String, instructions: Option<String>, language: String, short: bool) -> Result<String> {
        self.inner.create_audio(&notebook_id, instructions.as_deref(), &language, short).await
    }

    pub async fn artifact_state(&self, notebook_id: String, artifact_id: String) -> Result<ArtifactState> {
        let row = self.inner.artifact(&notebook_id, &artifact_id).await?;
        let status = match row.status {
            rows::ArtifactStatus::Pending => "pending",
            rows::ArtifactStatus::Completed => "completed",
            rows::ArtifactStatus::Failed => "failed",
            rows::ArtifactStatus::NotFound => "not_found",
        };
        Ok(ArtifactState { status: status.into(), audio_url: row.audio_url })
    }

    /// JSON brut du quiz NotebookLM (`{"quiz":[{question, answerOptions[], hint}]}`).
    pub async fn quiz_json(&self, notebook_id: String, artifact_id: String) -> Result<String> {
        self.inner.quiz_json(&notebook_id, &artifact_id).await
    }

    /// Télécharge un média (audio m4a) vers `dest_path` ; renvoie la taille en octets.
    pub async fn download_media(&self, url: String, dest_path: String) -> Result<u64> {
        self.inner.download_media(&url, std::path::Path::new(&dest_path)).await
    }
}

/// Version de la bibliothèque (diagnostic).
#[uniffi::export]
pub fn nlm_core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[allow(dead_code)]
fn _assert_error_is_send(e: NlmError) -> impl Send { e }
