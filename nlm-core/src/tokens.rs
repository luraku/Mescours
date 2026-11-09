//! Extraction des jetons de session de la page d'accueil NotebookLM
//! (`WIZ_global_data` : `SNlM0e` = jeton CSRF `at`, `FdrFJe` = `f.sid`).

use once_cell::sync::Lazy;
use regex::Regex;

#[derive(Clone, Debug, PartialEq)]
pub struct Tokens {
    pub csrf: String,
    pub session_id: String,
    /// Libellé de build (`cfb2h`), utilisé comme `bl` du chat s'il est présent.
    pub build_label: Option<String>,
}

/// Valeur d'un champ `WIZ_global_data` (mêmes motifs que `_auth/extraction.py`).
pub fn wiz_field(html: &str, key: &str) -> Option<String> {
    let k = regex::escape(key);
    let double = Regex::new(&format!(r#""{k}"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)""#)).ok()?;
    if let Some(c) = double.captures(html) {
        return Some(c[1].to_string());
    }
    let single = Regex::new(&format!(r#"'{k}'\s*:\s*'([^'\\]*(?:\\.[^'\\]*)*)'"#)).ok()?;
    if let Some(c) = single.captures(html) {
        return Some(c[1].to_string());
    }
    // Variante HTML-échappée : &quot;KEY&quot; : &quot;valeur&quot;
    static SEP: Lazy<Regex> = Lazy::new(|| Regex::new(r"^\s*:\s*&quot;").unwrap());
    let marker = format!("&quot;{key}&quot;");
    let start = html.find(&marker)? + marker.len();
    let rest = &html[start..];
    let m = SEP.find(rest)?;
    let value = &rest[m.end()..];
    Some(value[..value.find("&quot;")?].to_string())
}

pub fn extract(html: &str) -> Option<Tokens> {
    let csrf = wiz_field(html, "SNlM0e")?;
    let session_id = wiz_field(html, "FdrFJe").unwrap_or_default();
    let build_label = wiz_field(html, "cfb2h").filter(|s| s.starts_with("boq_"));
    Some(Tokens { csrf, session_id, build_label })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_from_variants() {
        let t = extract(r#"<script>window.WIZ_global_data = {"SNlM0e":"AF1_x:123","FdrFJe":"-42","cfb2h":"boq_labs-tailwind-frontend_20260111.16_p0"};</script>"#).unwrap();
        assert_eq!(t.csrf, "AF1_x:123");
        assert_eq!(t.session_id, "-42");
        assert_eq!(t.build_label.as_deref(), Some("boq_labs-tailwind-frontend_20260111.16_p0"));
        assert_eq!(wiz_field("{'SNlM0e':'abc'}", "SNlM0e").as_deref(), Some("abc"));
        assert_eq!(wiz_field("&quot;SNlM0e&quot; : &quot;q1&quot;,", "SNlM0e").as_deref(), Some("q1"));
        assert!(extract("<html>rien</html>").is_none());
    }
}
