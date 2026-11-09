//! Encodage/décodage du protocole `batchexecute` et du flux de chat de NotebookLM
//! (fidèle à notebooklm-py : `_web/wire/encoder.py`, `_web/wire/decoder.py`,
//! `_web/rows/chat_stream.py`).

use crate::error::{NlmError, Result};
use serde_json::{json, Value};

/// JSON compact « à la Python » : `json.dumps(…, separators=(",", ":"))` avec
/// `ensure_ascii=True` (tout caractère hors ASCII imprimable échappé en `\uXXXX`).
pub fn py_json(v: &Value) -> String {
    let raw = serde_json::to_string(v).expect("JSON sérialisable");
    let mut out = String::with_capacity(raw.len());
    for ch in raw.chars() {
        if (ch as u32) < 0x7f {
            out.push(ch);
        } else {
            let mut buf = [0u16; 2];
            for unit in ch.encode_utf16(&mut buf) {
                out.push_str(&format!("\\u{:04x}", unit));
            }
        }
    }
    out
}

/// `urllib.parse.quote(s, safe="")` : tout encodé sauf `A-Z a-z 0-9 _ . - ~`.
pub fn pct(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b'-' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

/// Encodage application/x-www-form-urlencoded d'un paramètre d'URL (`urlencode`).
pub fn query_value(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

fn form_body(f_req: &str, at: &str) -> String {
    let mut body = format!("f.req={}", pct(f_req));
    if !at.is_empty() {
        body.push_str("&at=");
        body.push_str(&pct(at));
    }
    body.push('&');
    body
}

/// Corps d'un appel batchexecute : `f.req=[[[id,"<params json>",null,"generic"]]]&at=…&`.
pub fn batch_body(rpc_id: &str, params: &Value, at: &str) -> String {
    let f_req = py_json(&json!([[[rpc_id, py_json(params), null, "generic"]]]));
    form_body(&f_req, at)
}

/// Corps de `GenerateFreeFormStreamed` : `f.req=[null,"<params json>"]&at=…&`.
pub fn chat_body(params: &Value, at: &str) -> String {
    let f_req = py_json(&json!([null, py_json(params)]));
    form_body(&f_req, at)
}

/// Découpe la réponse `)]}'` + blocs `<taille>\n<json>` en trames JSON.
/// Les tailles annoncées ne sont pas fiables : on analyse ligne par ligne.
pub fn frames(body: &str) -> Vec<Value> {
    let mut text = body;
    if let Some(rest) = text.strip_prefix(")]}'") {
        text = rest;
    }
    let mut out = Vec::new();
    for line in text.trim().split('\n') {
        let line = line.trim_end_matches('\r');
        if line.trim().is_empty() || line.trim().parse::<i64>().is_ok() {
            continue;
        }
        let Ok(chunk) = serde_json::from_str::<Value>(line) else { continue };
        match chunk {
            Value::Array(items) if items.first().map(Value::is_array).unwrap_or(false) => {
                out.extend(items.into_iter().filter(Value::is_array));
            }
            Value::Array(_) => out.push(chunk),
            _ => {}
        }
    }
    out
}

fn contains_str(v: &Value, needle: &str, depth: u32) -> bool {
    if depth > 20 {
        return false;
    }
    match v {
        Value::String(s) => s.contains(needle),
        Value::Array(a) => a.iter().any(|x| contains_str(x, needle, depth + 1)),
        Value::Object(o) => o.values().any(|x| contains_str(x, needle, depth + 1)),
        _ => false,
    }
}

fn grpc_code(status: &Value) -> Option<i64> {
    status.as_array().and_then(|a| a.first()).and_then(Value::as_i64)
}

/// Résultat décodé d'un RPC (`wrb.fr`), ou `Ok(Value::Null)` si le serveur renvoie un
/// résultat vide sans statut d'erreur (certains RPC le tolèrent).
pub fn rpc_result(body: &str, rpc_id: &str) -> Result<Value> {
    let fr = frames(body);
    if fr.is_empty() {
        return Err(NlmError::protocol("réponse sans données RPC"));
    }
    let mut result: Option<Value> = None;
    let mut status: Option<Value> = None;
    let mut seen_other = false;
    for f in &fr {
        let tag = f.get(0).and_then(Value::as_str).unwrap_or_default();
        let id = f.get(1).and_then(Value::as_str);
        match tag {
            "er" if id == Some(rpc_id) => {
                return Err(NlmError::protocol(format!("erreur RPC {rpc_id} : {}", f.get(2).cloned().unwrap_or(Value::Null))));
            }
            "wrb.fr" if id == Some(rpc_id) => match f.get(2) {
                Some(Value::String(s)) => {
                    let parsed = serde_json::from_str::<Value>(s).unwrap_or(Value::String(s.clone()));
                    if !parsed.is_null() {
                        result = Some(parsed);
                    }
                }
                _ => {
                    if let Some(st) = f.get(5).filter(|v| !v.is_null()) {
                        if contains_str(st, "UserDisplayableError", 0) {
                            return Err(NlmError::rate("quota ou limite de débit NotebookLM atteint"));
                        }
                        status = Some(st.clone());
                    }
                }
            },
            "wrb.fr" | "er" => seen_other = true,
            _ => {}
        }
    }
    if let Some(r) = result {
        // Le format d'erreur d'identifiants peut arriver comme résultat : ["INVALID_CREDENTIALS"].
        if r.as_array().and_then(|a| a.first()).and_then(Value::as_str) == Some("INVALID_CREDENTIALS") {
            return Err(NlmError::auth("identifiants refusés"));
        }
        return Ok(r);
    }
    if let Some(st) = status {
        return Err(match grpc_code(&st) {
            Some(5) => NlmError::not_found(format!("{rpc_id} : ressource introuvable (mauvais compte ?)")),
            Some(7) => NlmError::auth(format!("{rpc_id} : accès refusé (mauvais compte ?)")),
            Some(16) => NlmError::auth(format!("{rpc_id} : non authentifié")),
            Some(8) => NlmError::rate(format!("{rpc_id} : ressources épuisées")),
            Some(3) => NlmError::rejected(format!("{rpc_id} : demande invalide ou limite atteinte")),
            Some(c) => NlmError::protocol(format!("{rpc_id} : statut gRPC {c}")),
            None => NlmError::protocol(format!("{rpc_id} : statut {st}")),
        });
    }
    if seen_other {
        return Err(NlmError::protocol(format!("identifiant RPC {rpc_id} absent de la réponse (changé ?)")));
    }
    Ok(Value::Null)
}

/// Réponse finale d'un chat diffusé en flux : le bloc « réponse » (marqueur 1) final.
pub fn chat_answer(body: &str) -> Result<String> {
    let fr = frames(body);
    let mut decoded = 0;
    let mut final_answer: Option<String> = None;
    let mut longest_answer = String::new();
    let mut longest_other = String::new();
    let mut saw_end = false;
    for f in &fr {
        match f.get(0).and_then(Value::as_str) {
            Some("e") => saw_end = true,
            Some("er") => return Err(NlmError::protocol(format!("erreur du chat : {f}"))),
            Some("wrb.fr") => match f.get(2) {
                Some(Value::String(s)) => {
                    let Ok(inner) = serde_json::from_str::<Value>(s) else { continue };
                    decoded += 1;
                    let Some(row) = inner.get(0).filter(|r| r.as_array().map(|a| !a.is_empty()).unwrap_or(false)) else {
                        continue;
                    };
                    let text = row.get(0).and_then(Value::as_str).unwrap_or_default().to_string();
                    let marker = row.get(4).and_then(|t| t.get(4)).and_then(Value::as_i64);
                    let is_final = inner.get(4).and_then(Value::as_bool) == Some(true);
                    match marker {
                        Some(1) => {
                            if is_final {
                                final_answer = Some(text.clone());
                            }
                            if text.len() > longest_answer.len() {
                                longest_answer = text;
                            }
                        }
                        Some(2) => {} // « réflexion » du modèle : jamais renvoyée
                        _ => {
                            if text.len() > longest_other.len() {
                                longest_other = text;
                            }
                        }
                    }
                }
                _ => {
                    let st = f.get(5).cloned().unwrap_or(Value::Null);
                    if contains_str(&st, "UserDisplayableError", 0) {
                        return Err(NlmError::rate("quota de chat NotebookLM atteint"));
                    }
                    if !st.is_null() {
                        return Err(NlmError::rejected(format!("question refusée (statut {st})")));
                    }
                }
            },
            _ => {}
        }
    }
    if decoded == 0 {
        return Err(if saw_end {
            NlmError::rate("quota de chat NotebookLM épuisé")
        } else {
            NlmError::protocol("flux de chat illisible")
        });
    }
    Ok(final_answer
        .or_else(|| (!longest_answer.is_empty()).then_some(longest_answer))
        .unwrap_or(longest_other))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn py_json_escapes_like_python() {
        assert_eq!(py_json(&json!(["\u{e9}", "a/b", "\u{1F600}"])), r#"["\u00e9","a/b","\ud83d\ude00"]"#);
        assert_eq!(py_json(&json!([null, 1, null, [2]])), "[null,1,null,[2]]");
    }

    #[test]
    fn batch_body_matches_recorded_request() {
        // Cassette notebooks_list.yaml, interaction #1.
        let body = batch_body("wXbhsf", &json!([null, 1, null, [2]]), "SCRUBBED_CSRF:1768312210632");
        assert_eq!(
            body,
            "f.req=%5B%5B%5B%22wXbhsf%22%2C%22%5Bnull%2C1%2Cnull%2C%5B2%5D%5D%22%2Cnull%2C%22generic%22%5D%5D%5D&at=SCRUBBED_CSRF%3A1768312210632&"
        );
    }

    #[test]
    fn decodes_frames_and_errors() {
        let ok = ")]}'\n\n181\n[[\"wrb.fr\",\"ZwVcOc\",\"[[null,[6,500,300,500000],[true,null,null,true,[\\\"fr\\\"]],[[1]],[true,1,3,2]]]\",null,null,null,\"generic\"],[\"di\",125]]\n23\n[[\"e\",4,null,null,219]]\n";
        let v = rpc_result(ok, "ZwVcOc").unwrap();
        assert_eq!(v[0][2][4][0], "fr");
        let rate = ")]}'\n\n9\n[[\"wrb.fr\",\"wXbhsf\",null,null,null,[8,null,[[\"type.googleapis.com/x.UserDisplayableError\",[]]]]]]\n";
        assert!(matches!(rpc_result(rate, "wXbhsf"), Err(NlmError::RateLimited { .. })));
        let rejected = ")]}'\n\n9\n[[\"wrb.fr\",\"CCqFvf\",null,null,null,[3],\"generic\"]]\n";
        assert!(matches!(rpc_result(rejected, "CCqFvf"), Err(NlmError::Rejected { .. })));
        let creds = ")]}'\n\n59\n[[\"wrb.fr\",\"wXbhsf\",\"[\\\"INVALID_CREDENTIALS\\\"]\",null,null]]\n";
        assert!(matches!(rpc_result(creds, "wXbhsf"), Err(NlmError::AuthExpired { .. })));
        let other = ")]}'\n\n9\n[[\"wrb.fr\",\"zzz\",\"[1]\",null,null]]\n";
        assert!(matches!(rpc_result(other, "wXbhsf"), Err(NlmError::Protocol { .. })));
    }

    #[test]
    fn chat_rejection_and_quota() {
        let rej = ")]}'\n\n36\n[[\"wrb.fr\",null,null,null,null,[3]]]\n23\n[[\"e\",4,null,null,132]]\n";
        assert!(matches!(chat_answer(rej), Err(NlmError::Rejected { .. })));
        let quota = ")]}'\n\n23\n[[\"e\",4,null,null,132]]\n";
        assert!(matches!(chat_answer(quota), Err(NlmError::RateLimited { .. })));
    }
}
