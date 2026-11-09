//! Vérifie le codec et les lecteurs de réponses sur des échanges réels enregistrés
//! (cassettes de notebooklm-py, voir tests/fixtures/README.md).

use nlm_core::{rows, tokens, wire};
use serde_json::{json, Value};

fn fixture(name: &str) -> Vec<Value> {
    let path = format!("{}/tests/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn rpc_call<'a>(items: &'a [Value], rpc_id: &str) -> &'a Value {
    items
        .iter()
        .find(|i| i["uri"].as_str().unwrap().contains(&format!("rpcids={rpc_id}")))
        .unwrap_or_else(|| panic!("appel {rpc_id} absent"))
}

/// Paramètre `at` du corps enregistré (pour reconstruire le corps à l'identique).
fn recorded_at(body: &str) -> String {
    let at = body.split('&').find_map(|kv| kv.strip_prefix("at=")).unwrap_or_default();
    url::form_urlencoded::parse(format!("x={at}").as_bytes()).next().map(|(_, v)| v.into_owned()).unwrap_or_default()
}

fn rpc_result(item: &Value, rpc_id: &str) -> Value {
    wire::rpc_result(item["response_body"].as_str().unwrap(), rpc_id).unwrap()
}

#[test]
fn homepage_tokens() {
    let items = fixture("notebooks_list");
    let t = tokens::extract(items[0]["response_body"].as_str().unwrap()).expect("jetons");
    assert_eq!(t.csrf, "SCRUBBED_CSRF");
    assert_eq!(t.session_id, "SCRUBBED_SESSION");
    assert!(t.build_label.unwrap().starts_with("boq_labs-tailwind-frontend_"));
}

#[test]
fn list_notebooks() {
    let items = fixture("notebooks_list");
    let call = rpc_call(&items, "wXbhsf");
    assert_eq!(rows::notebook_count(&rpc_result(call, "wXbhsf")), 12);
}

#[test]
fn create_notebook_request_and_response() {
    let items = fixture("notebooks_create");
    let call = rpc_call(&items, "CCqFvf");
    let body = call["request_body"].as_str().unwrap();
    let ours = wire::batch_body("CCqFvf", &json!(["VCR Test Notebook", null, null, rows::template()]), &recorded_at(body));
    assert_eq!(ours, body, "corps CREATE_NOTEBOOK identique à l'original");
    assert_eq!(rows::created_notebook_id(&rpc_result(call, "CCqFvf")).unwrap(), "afefc562-f8d1-41ec-a5d5-c197efdf52e1");
}

#[test]
fn register_file_source() {
    let items = fixture("sources_add_file");
    let call = rpc_call(&items, "o4cbdc");
    let body = call["request_body"].as_str().unwrap();
    let ours = wire::batch_body(
        "o4cbdc",
        &json!([[["vcr_test_document.txt"]], "06f0c5bd-108f-4c8b-8911-34b2acc656de", rows::template()]),
        &recorded_at(body),
    );
    assert_eq!(ours, body);
    let id = rows::registered_source_id(&rpc_result(call, "o4cbdc"), "vcr_test_document.txt").unwrap();
    assert_eq!(id, "dc84ca28-2629-49ac-aec3-de45f0ec93e4");
    // Étapes Scotty enregistrées : en-tête X-Goog-Upload-URL puis « OK ».
    let start = items.iter().find(|i| i["uri"].as_str().unwrap().contains("/upload/_/?authuser=0")).unwrap();
    let headers = start["response_headers"].as_object().unwrap();
    assert!(headers.keys().any(|k| k.eq_ignore_ascii_case("x-goog-upload-url")));
}

#[test]
fn notebook_source_statuses() {
    let items = fixture("server_add_file");
    let call = rpc_call(&items, "rLM1Ne");
    let sources = rows::notebook_sources(&rpc_result(call, "rLM1Ne")).unwrap();
    assert!(sources.len() >= 3);
    let preparing = sources.iter().find(|s| s.id == "b5f61712-5710-459e-8beb-63b971aca467").unwrap();
    assert_eq!(preparing.status, 5);
    assert!(sources.iter().any(|s| s.status == 2));
}

#[test]
fn output_language() {
    let items = fixture("settings_get_output_language");
    let call = rpc_call(&items, "ZwVcOc");
    assert_eq!(rows::output_language(&rpc_result(call, "ZwVcOc")).as_deref(), Some("fr"));
}

#[test]
fn create_quiz_response() {
    let items = fixture("server_generate_quiz");
    let call = rpc_call(&items, "R7cb6c");
    assert_eq!(rows::created_artifact_id(&rpc_result(call, "R7cb6c")).unwrap(), "33dc9431-cd17-4a47-aa14-1c3ef420358b");
}

#[test]
fn audio_artifact_url() {
    let items = fixture("artifacts_list_audio");
    let call = rpc_call(&items, "gArtLc");
    let row = rows::artifact_row(&rpc_result(call, "gArtLc"), "575a9e5d-40fb-44a4-b2d3-21a573bdb547");
    assert_eq!(row.status, rows::ArtifactStatus::Completed);
    let url = row.audio_url.unwrap();
    assert!(url.starts_with("https://lh3.googleusercontent.com/notebooklm/") && url.ends_with("=m140-dv"), "{url}");
    assert_eq!(rows::artifact_row(&rpc_result(call, "gArtLc"), "inconnu").status, rows::ArtifactStatus::NotFound);
}

#[test]
fn quiz_download() {
    let items = fixture("artifacts_download_quiz");
    let call = rpc_call(&items, "v9rmvd");
    let quiz: Value = serde_json::from_str(&rows::quiz_json_from_result(&rpc_result(call, "v9rmvd")).unwrap()).unwrap();
    let questions = quiz["quiz"].as_array().unwrap();
    assert_eq!(questions.len(), 10);
    let q = &questions[0];
    assert!(q["question"].as_str().unwrap().starts_with("What is the core philosophy"));
    let options = q["answerOptions"].as_array().unwrap();
    assert_eq!(options.iter().filter(|o| o["isCorrect"] == true).count(), 1);
    assert!(options[0]["text"].as_str().unwrap().contains("agent's"), "entités HTML décodées");
}

#[test]
fn chat_answer_and_request() {
    let items = fixture("chat_ask");
    let call = items.iter().find(|i| i["uri"].as_str().unwrap().contains("GenerateFreeFormStreamed")).unwrap();
    let body = call["request_body"].as_str().unwrap();
    let params = json!([
        [[["466b9ee3-c1ce-45ef-861c-1d4bfcd939ad"]]],
        "What is this notebook about?",
        null,
        [2, null, [1], [1]],
        null, null, null,
        "bb00c9e3-656c-4fd2-b890-2b71e1cf3814",
        1
    ]);
    assert_eq!(wire::chat_body(&params, &recorded_at(body)), body, "corps du chat identique à l'original");
    let answer = wire::chat_answer(call["response_body"].as_str().unwrap()).unwrap();
    assert!(answer.starts_with("This notebook is about **NotebookLM**"), "{}", &answer[..80.min(answer.len())]);
    assert!(!answer.contains("Defining the Context"), "la « réflexion » n'est pas renvoyée");
}

#[test]
fn chat_rejection() {
    let items = fixture("chat_ask_oversized_rejection");
    let call = items.iter().find(|i| i["uri"].as_str().unwrap().contains("GenerateFreeFormStreamed")).unwrap();
    assert!(wire::chat_answer(call["response_body"].as_str().unwrap()).is_err());
}

#[test]
fn source_fulltext() {
    let items = fixture("sources_get_fulltext");
    let call = rpc_call(&items, "hizoJc");
    let text = rows::source_text(&rpc_result(call, "hizoJc"));
    assert!(text.starts_with("Skip to content"), "{}", &text[..60.min(text.len())]);
    assert!(text.lines().count() > 10);
}
