//! Bocal à cookies minimal (RFC 6265 simplifiée) : charge un `storage_state` Playwright
//! (format produit par l'app ou par Cookie-Editor), envoie les bons cookies par hôte,
//! applique les `Set-Cookie` (dont la rotation de `__Secure-1PSIDTS`) et se ré-exporte.

use crate::error::{NlmError, Result};
use reqwest::header::HeaderValue;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::sync::Mutex;
use url::Url;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct StoredCookie {
    pub name: String,
    pub value: String,
    /// `.google.com` = cookie de domaine ; `notebook.google.com` = cookie d'hôte.
    pub domain: String,
    #[serde(default = "default_path")]
    pub path: String,
    #[serde(default)]
    pub secure: bool,
    #[serde(default, rename = "httpOnly")]
    pub http_only: bool,
    #[serde(default = "default_expires")]
    pub expires: f64,
    #[serde(default = "default_same_site", rename = "sameSite")]
    pub same_site: String,
}

fn default_path() -> String { "/".into() }
fn default_expires() -> f64 { -1.0 }
fn default_same_site() -> String { "Lax".into() }

#[derive(Default)]
pub struct Jar {
    cookies: Mutex<Vec<StoredCookie>>,
}

impl Jar {
    /// Accepte `{"cookies":[…]}` (Playwright) ou une liste brute (Cookie-Editor, etc.).
    pub fn from_json(json: &str) -> Result<Self> {
        let v: Value = serde_json::from_str(json)
            .map_err(|e| NlmError::auth(format!("cookies illisibles : {e}")))?;
        let list = match &v {
            Value::Object(o) => o.get("cookies").cloned().unwrap_or(Value::Array(vec![])),
            Value::Array(_) => v.clone(),
            _ => return Err(NlmError::auth("format de cookies inconnu")),
        };
        let mut out = Vec::new();
        for c in list.as_array().cloned().unwrap_or_default() {
            let name = c.get("name").and_then(Value::as_str).unwrap_or_default();
            let value = c.get("value").and_then(Value::as_str).unwrap_or_default();
            let mut domain = c.get("domain").and_then(Value::as_str).unwrap_or_default().to_string();
            if name.is_empty() || domain.is_empty() {
                continue;
            }
            // Format Cookie-Editor : hostOnly=false sur un domaine sans point initial.
            if c.get("hostOnly").and_then(Value::as_bool) == Some(false) && !domain.starts_with('.') {
                domain = format!(".{domain}");
            }
            out.push(StoredCookie {
                name: name.into(),
                value: value.into(),
                domain,
                path: c.get("path").and_then(Value::as_str).unwrap_or("/").into(),
                secure: c.get("secure").and_then(Value::as_bool).unwrap_or(name.starts_with("__Secure-") || name.starts_with("__Host-")),
                http_only: c.get("httpOnly").and_then(Value::as_bool).unwrap_or(true),
                expires: c.get("expires").or_else(|| c.get("expirationDate")).and_then(Value::as_f64).unwrap_or(-1.0),
                same_site: c.get("sameSite").and_then(Value::as_str).unwrap_or("Lax").into(),
            });
        }
        if !out.iter().any(|c| c.name == "SID") {
            return Err(NlmError::auth("cookie SID absent : connectez-vous à Google dans l'app"));
        }
        Ok(Self { cookies: Mutex::new(out) })
    }

    /// Ré-export au format `storage_state` (pour que l'app conserve les cookies renouvelés).
    pub fn to_json(&self) -> String {
        let cookies = self.cookies.lock().unwrap().clone();
        serde_json::json!({ "cookies": cookies, "origins": [] }).to_string()
    }

    pub fn has(&self, name: &str) -> bool {
        self.cookies.lock().unwrap().iter().any(|c| c.name == name && !c.value.is_empty())
    }

    fn domain_matches(cookie_domain: &str, host: &str) -> bool {
        match cookie_domain.strip_prefix('.') {
            Some(d) => host == d || host.ends_with(&format!(".{d}")),
            None => host == cookie_domain,
        }
    }

    fn path_matches(cookie_path: &str, path: &str) -> bool {
        cookie_path == "/" || path == cookie_path || path.starts_with(&format!("{}/", cookie_path.trim_end_matches('/')))
    }

    /// Valeur de l'en-tête `Cookie` pour une URL (None si aucun cookie).
    pub fn header_for(&self, url: &Url) -> Option<String> {
        let host = url.host_str()?;
        let https = url.scheme() == "https";
        let mut matched: Vec<StoredCookie> = self
            .cookies
            .lock()
            .unwrap()
            .iter()
            .filter(|c| Self::domain_matches(&c.domain, host))
            .filter(|c| Self::path_matches(&c.path, url.path()))
            .filter(|c| https || !c.secure)
            .cloned()
            .collect();
        if matched.is_empty() {
            return None;
        }
        // Chemins les plus longs d'abord (RFC 6265 §5.4).
        matched.sort_by(|a, b| b.path.len().cmp(&a.path.len()));
        Some(matched.iter().map(|c| format!("{}={}", c.name, c.value)).collect::<Vec<_>>().join("; "))
    }

    /// Applique un en-tête `Set-Cookie` reçu de `url`.
    pub fn store(&self, set_cookie: &str, url: &Url) {
        let Some(host) = url.host_str() else { return };
        let mut parts = set_cookie.split(';');
        let Some((name, value)) = parts.next().and_then(|nv| nv.split_once('=')) else { return };
        let (name, value) = (name.trim().to_string(), value.trim().to_string());
        let mut domain = host.to_string();
        let mut path = "/".to_string();
        let mut secure = false;
        let mut http_only = false;
        let mut delete = false;
        for attr in parts {
            let (k, v) = attr.split_once('=').map(|(k, v)| (k.trim(), v.trim())).unwrap_or((attr.trim(), ""));
            match k.to_ascii_lowercase().as_str() {
                "domain" if !v.is_empty() => {
                    let d = v.trim_start_matches('.').to_ascii_lowercase();
                    // Refuse un domaine étranger à l'hôte qui répond.
                    if host == d || host.ends_with(&format!(".{d}")) {
                        domain = format!(".{d}");
                    } else {
                        return;
                    }
                }
                "path" if v.starts_with('/') => path = v.to_string(),
                "secure" => secure = true,
                "httponly" => http_only = true,
                "max-age" => delete = v.parse::<i64>().map(|n| n <= 0).unwrap_or(false),
                "expires" => delete = delete || v.contains("1970") || v.contains("1990"),
                _ => {}
            }
        }
        let mut jar = self.cookies.lock().unwrap();
        jar.retain(|c| !(c.name == name && c.domain == domain && c.path == path));
        if !delete {
            jar.push(StoredCookie {
                name,
                value,
                domain,
                path,
                secure,
                http_only,
                expires: -1.0,
                same_site: "Lax".into(),
            });
        }
    }
}

impl reqwest::cookie::CookieStore for Jar {
    fn set_cookies(&self, cookie_headers: &mut dyn Iterator<Item = &HeaderValue>, url: &Url) {
        for h in cookie_headers {
            if let Ok(s) = h.to_str() {
                self.store(s, url);
            }
        }
    }

    fn cookies(&self, url: &Url) -> Option<HeaderValue> {
        self.header_for(url).and_then(|s| HeaderValue::from_str(&s).ok())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jar() -> Jar {
        Jar::from_json(r#"{"cookies":[
            {"name":"SID","value":"s","domain":".google.com","path":"/"},
            {"name":"__Secure-1PSIDTS","value":"t","domain":".google.com","path":"/","secure":true},
            {"name":"OSID","value":"o","domain":"notebook.google.com","path":"/"},
            {"name":"LSID","value":"l","domain":"accounts.google.com","path":"/"}
        ]}"#).unwrap()
    }

    #[test]
    fn domain_and_host_matching() {
        let j = jar();
        let h = j.header_for(&Url::parse("https://notebook.google.com/x").unwrap()).unwrap();
        assert!(h.contains("SID=s") && h.contains("OSID=o") && !h.contains("LSID"));
        let a = j.header_for(&Url::parse("https://accounts.google.com/RotateCookies").unwrap()).unwrap();
        assert!(a.contains("LSID=l") && !a.contains("OSID"));
        assert!(j.header_for(&Url::parse("https://example.com/").unwrap()).is_none());
        // Cookie sécurisé jamais envoyé en clair.
        let http = j.header_for(&Url::parse("http://notebook.google.com/").unwrap()).unwrap();
        assert!(!http.contains("__Secure-1PSIDTS"));
    }

    #[test]
    fn set_cookie_rotation_and_deletion() {
        let j = jar();
        let u = Url::parse("https://accounts.google.com/RotateCookies").unwrap();
        j.store("__Secure-1PSIDTS=neuf; Domain=.google.com; Path=/; Secure; HttpOnly", &u);
        let h = j.header_for(&Url::parse("https://notebook.google.com/").unwrap()).unwrap();
        assert!(h.contains("__Secure-1PSIDTS=neuf") && !h.contains("__Secure-1PSIDTS=t"));
        j.store("OSID=; Domain=evil.com", &u); // domaine étranger ignoré
        j.store("LSID=x; Max-Age=0; Path=/", &u);
        assert!(!j.header_for(&u).unwrap().contains("LSID"));
        assert!(j.to_json().contains("neuf"));
    }

    #[test]
    fn cookie_editor_format_and_missing_sid() {
        let j = Jar::from_json(r#"[{"name":"SID","value":"s","domain":"google.com","hostOnly":false}]"#).unwrap();
        assert!(j.header_for(&Url::parse("https://notebook.google.com/").unwrap()).is_some());
        assert!(Jar::from_json(r#"[{"name":"NID","value":"n","domain":".google.com"}]"#).is_err());
    }
}
