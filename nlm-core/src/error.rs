//! Erreurs exposées à Kotlin (via UniFFI).

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum NlmError {
    /// Session Google absente, expirée ou refusée : il faut se reconnecter.
    #[error("Session Google expirée ou invalide : {message}")]
    AuthExpired { message: String },
    /// Quota ou limite de débit NotebookLM atteinte.
    #[error("Limite NotebookLM atteinte : {message}")]
    RateLimited { message: String },
    #[error("Introuvable : {message}")]
    NotFound { message: String },
    /// Requête refusée par NotebookLM (limite de carnets/sources, contenu refusé…).
    #[error("NotebookLM a refusé la demande : {message}")]
    Rejected { message: String },
    #[error("Erreur réseau : {message}")]
    Network { message: String },
    /// Réponse inattendue : le format de NotebookLM a probablement changé.
    #[error("Réponse NotebookLM inattendue : {message}")]
    Protocol { message: String },
    #[error("Erreur de fichier : {message}")]
    Io { message: String },
}

impl NlmError {
    pub fn protocol(m: impl Into<String>) -> Self { Self::Protocol { message: m.into() } }
    pub fn auth(m: impl Into<String>) -> Self { Self::AuthExpired { message: m.into() } }
    pub fn rejected(m: impl Into<String>) -> Self { Self::Rejected { message: m.into() } }
    pub fn not_found(m: impl Into<String>) -> Self { Self::NotFound { message: m.into() } }
    pub fn rate(m: impl Into<String>) -> Self { Self::RateLimited { message: m.into() } }
}

impl From<reqwest::Error> for NlmError {
    fn from(e: reqwest::Error) -> Self {
        NlmError::Network { message: e.to_string() }
    }
}

impl From<std::io::Error> for NlmError {
    fn from(e: std::io::Error) -> Self {
        NlmError::Io { message: e.to_string() }
    }
}

pub type Result<T> = std::result::Result<T, NlmError>;
