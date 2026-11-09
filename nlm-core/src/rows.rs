//! Lecture des réponses positionnelles des RPC (indices d'après notebooklm-py v0.8.6).

use crate::error::{NlmError, Result};
use serde_json::Value;

/// Bloc « modèle » commun à plusieurs RPC (`build_template_block`).
pub fn template() -> Value {
    serde_json::json!([2, null, null, [1, null, null, null, null, null, null, null, null, null, [1]]])
}

/// Options client de CREATE_ARTIFACT (`_artifact_client_options`).
pub fn artifact_options() -> Value {
    serde_json::json!([2, null, null, [1, null, null, null, null, null, null, null, null, null, [1]], [[1, 4, 8, 2, 3, 6]]])
}

pub fn notebook_count(result: &Value) -> u32 {
    result.get(0).and_then(Value::as_array).map(|a| a.len() as u32).unwrap_or(0)
}

/// CREATE_NOTEBOOK : ligne de carnet nue, id en `[2]`.
pub fn created_notebook_id(result: &Value) -> Result<String> {
    result
        .get(2)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| NlmError::protocol("identifiant du carnet créé introuvable"))
}

/// Identifiant d'une ligne de source : `["id"]`, `"id"` ou Drive `[null,true,["id"]]`.
fn source_row_id(row: &Value) -> Option<String> {
    let env = row.get(0)?;
    if let Some(s) = env.as_str() {
        return Some(s.to_string());
    }
    if let Some(s) = env.get(0).and_then(Value::as_str) {
        return Some(s.to_string());
    }
    env.get(2).and_then(|x| x.get(0)).and_then(Value::as_str).map(str::to_string)
}

/// État d'une source : 1 traitement, 2 prête, 3 erreur, 5 préparation.
#[derive(Debug, Clone, PartialEq)]
pub struct SourceState {
    pub id: String,
    pub title: String,
    pub status: i64,
}

/// GET_NOTEBOOK : sources en `result[0][1]`, statut en `source[3][1]`.
pub fn notebook_sources(result: &Value) -> Result<Vec<SourceState>> {
    let nb = result
        .get(0)
        .filter(|v| v.as_array().map(|a| !a.is_empty()).unwrap_or(false))
        .ok_or_else(|| NlmError::not_found("carnet introuvable"))?;
    let rows = nb.get(1).and_then(Value::as_array).cloned().unwrap_or_default();
    Ok(rows
        .iter()
        .filter_map(|r| {
            Some(SourceState {
                id: source_row_id(r)?,
                title: r.get(1).and_then(Value::as_str).unwrap_or_default().to_string(),
                status: r.get(3).and_then(|s| s.get(1)).and_then(Value::as_i64).unwrap_or(0),
            })
        })
        .collect())
}

/// ADD_SOURCE_FILE : l'id est l'élément couplé au nom de fichier (`[["id"], "fichier", …]`).
pub fn registered_source_id(result: &Value, filename: &str) -> Result<String> {
    fn walk(v: &Value, filename: &str, out: &mut Vec<String>, depth: u32) {
        if depth > 12 {
            return;
        }
        if let Some(a) = v.as_array() {
            if a.len() >= 2 && a[1].as_str() == Some(filename) {
                let id = a[0].as_str().map(str::to_string).or_else(|| a[0].get(0).and_then(Value::as_str).map(str::to_string));
                if let Some(id) = id.filter(|s| plausible_id(s)) {
                    if !out.contains(&id) {
                        out.push(id);
                    }
                }
            }
            for x in a {
                walk(x, filename, out, depth + 1);
            }
        }
    }
    let mut ids = Vec::new();
    walk(result, filename, &mut ids, 0);
    match ids.len() {
        1 => Ok(ids.remove(0)),
        0 => Err(NlmError::protocol("identifiant de la source envoyée introuvable")),
        _ => Err(NlmError::protocol("plusieurs identifiants de source candidats")),
    }
}

fn plausible_id(s: &str) -> bool {
    s.len() >= 4
        && !s.contains([' ', '\t', '/'])
        && s.chars().any(|c| c.is_ascii_digit() || c == '-' || c == '_')
}

/// GET_SOURCE : texte du document (`result[3][0]`, paragraphes → éléments → texte).
pub fn source_text(result: &Value) -> String {
    let Some(blocks) = result.get(3).and_then(|d| d.get(0)).and_then(Value::as_array) else {
        return String::new();
    };
    let mut lines = Vec::new();
    for block in blocks {
        let Some(elements) = block.get(2).and_then(|p| p.get(0)).and_then(Value::as_array) else { continue };
        let line: String = elements
            .iter()
            .filter_map(|e| e.get(2).and_then(|run| run.get(0)).and_then(Value::as_str))
            .collect();
        if !line.trim().is_empty() {
            lines.push(line.trim_end().to_string());
        }
    }
    if !lines.is_empty() {
        return lines.join("\n");
    }
    // Repli : toutes les chaînes non vides (comme `extract_all_text` en amont).
    fn all(v: &Value, out: &mut Vec<String>, depth: u32) {
        if depth > 100 {
            return;
        }
        match v {
            Value::String(s) if !s.trim().is_empty() => out.push(s.clone()),
            Value::Array(a) => a.iter().for_each(|x| all(x, out, depth + 1)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    all(&result[3][0], &mut out, 0);
    out.join("\n")
}

/// Langue de sortie : GET → `result[0][2][4][0]`, SET → `result[2][4][0]`.
pub fn output_language(result: &Value) -> Option<String> {
    let settings = if result.get(0).map(Value::is_array).unwrap_or(false) { &result[0][2] } else { &result[2] };
    settings.get(4).and_then(|l| l.get(0)).and_then(Value::as_str).map(str::to_string).filter(|s| !s.is_empty())
}

/// CREATE_ARTIFACT : `result[0][0]` = identifiant de l'artefact (et de la tâche).
pub fn created_artifact_id(result: &Value) -> Result<String> {
    result
        .get(0)
        .and_then(|r| r.get(0))
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| NlmError::rejected("NotebookLM n'a pas accepté la génération (fonction indisponible ?)"))
}

#[derive(Debug, Clone, PartialEq)]
pub enum ArtifactStatus {
    Pending,
    Completed,
    Failed,
    NotFound,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ArtifactRow {
    pub status: ArtifactStatus,
    pub audio_url: Option<String>,
}

/// LIST_ARTIFACTS : retrouve l'artefact et lit son état (1/2 en cours, 3 prêt, 4 échec).
pub fn artifact_row(result: &Value, artifact_id: &str) -> ArtifactRow {
    let rows: Vec<Value> = match result.as_array() {
        Some(a) if a.len() == 1 && a[0].as_array().map(|x| x.is_empty() || x[0].is_array()).unwrap_or(false) => {
            a[0].as_array().cloned().unwrap_or_default()
        }
        Some(a) => a.clone(),
        None => vec![],
    };
    let Some(row) = rows.iter().find(|r| r.get(0).and_then(Value::as_str) == Some(artifact_id)) else {
        return ArtifactRow { status: ArtifactStatus::NotFound, audio_url: None };
    };
    let audio_url = audio_url(row);
    let kind = row.get(2).and_then(Value::as_i64);
    let status = match row.get(4).and_then(Value::as_i64) {
        // Un audio « prêt » sans URL de média est encore en cours de finalisation.
        Some(3) if kind == Some(1) && audio_url.is_none() => ArtifactStatus::Pending,
        Some(3) => ArtifactStatus::Completed,
        Some(4) => ArtifactStatus::Failed,
        _ => ArtifactStatus::Pending,
    };
    ArtifactRow { status, audio_url }
}

/// URL du média audio : `row[6][5]`, de préférence l'entrée `audio/mp4`.
pub fn audio_url(row: &Value) -> Option<String> {
    let media = row.get(6)?.get(5)?.as_array()?;
    let valid = |e: &Value| e.get(0).and_then(Value::as_str).filter(|u| u.starts_with("https://") || u.starts_with("http://")).map(str::to_string);
    media
        .iter()
        .find(|e| e.get(2).and_then(Value::as_str) == Some("audio/mp4") && valid(e).is_some())
        .and_then(valid)
        .or_else(|| media.iter().find_map(valid))
}

/// GET_INTERACTIVE_HTML : HTML en `result[0][9][0]`, JSON du quiz dans `data-app-data`.
pub fn quiz_json_from_result(result: &Value) -> Result<String> {
    let html = result
        .get(0)
        .and_then(|r| r.get(9))
        .and_then(|b| b.get(0))
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| NlmError::protocol("contenu du quiz absent"))?;
    quiz_json_from_html(html)
}

pub fn quiz_json_from_html(html: &str) -> Result<String> {
    let re = regex::Regex::new(r#"(?s)data-app-data="([^"]+)""#).unwrap();
    let raw = re
        .captures(html)
        .map(|c| c[1].to_string())
        .ok_or_else(|| NlmError::protocol("données du quiz introuvables dans la page"))?;
    let decoded = html_escape::decode_html_entities(&raw).to_string();
    let v: Value = serde_json::from_str(&decoded).map_err(|e| NlmError::protocol(format!("JSON du quiz invalide : {e}")))?;
    Ok(v.to_string())
}
