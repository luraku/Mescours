//! Parcours complet de l'API exportée contre un faux serveur NotebookLM (wiremock) qui
//! rejoue des réponses réelles : session, jeton CSRF renouvelé, carnet, envoi, chat, quiz.

use nlm_core::NotebookLmClient;
use serde_json::Value;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use wiremock::matchers::{body_string_contains, method, path, query_param};
use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

fn fixture(name: &str) -> Vec<Value> {
    let p = format!("{}/tests/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

fn body_of(items: &[Value], needle: &str) -> String {
    items
        .iter()
        .find(|i| i["uri"].as_str().unwrap().contains(needle))
        .unwrap()["response_body"]
        .as_str()
        .unwrap()
        .to_string()
}

const SESSION: &str = r#"{"cookies":[
  {"name":"SID","value":"s","domain":"127.0.0.1","path":"/","secure":false},
  {"name":"__Secure-1PSIDTS","value":"t","domain":"127.0.0.1","path":"/","secure":false}]}"#;

/// Page d'accueil : le premier jeton est « périmé » (refusé une fois par batchexecute).
struct Home(AtomicUsize);
impl Respond for Home {
    fn respond(&self, _: &Request) -> ResponseTemplate {
        let n = self.0.fetch_add(1, Ordering::SeqCst);
        ResponseTemplate::new(200).set_body_string(format!(
            r#"<script>window.WIZ_global_data = {{"SNlM0e":"csrf-{n}","FdrFJe":"-77","cfb2h":"boq_labs-tailwind-frontend_20260111.16_p0"}};</script>"#
        ))
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn full_course_flow() {
    let server = MockServer::start().await;
    let home = Arc::new(Home(AtomicUsize::new(0)));
    Mock::given(method("GET")).and(path("/")).respond_with(HomeRef(home.clone())).mount(&server).await;

    // LIST_NOTEBOOKS : refusé avec le premier jeton (HTTP 400), accepté ensuite.
    Mock::given(method("POST"))
        .and(query_param("rpcids", "wXbhsf"))
        .and(body_string_contains("at=csrf-0"))
        .respond_with(ResponseTemplate::new(400))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(query_param("rpcids", "wXbhsf"))
        .and(body_string_contains("at=csrf-1"))
        .respond_with(ResponseTemplate::new(200).set_body_string(body_of(&fixture("notebooks_list"), "rpcids=wXbhsf")))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(query_param("rpcids", "ZwVcOc"))
        .respond_with(ResponseTemplate::new(200).set_body_string(body_of(&fixture("settings_get_output_language"), "rpcids=ZwVcOc")))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(query_param("rpcids", "CCqFvf"))
        .and(query_param("source-path", "/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(body_of(&fixture("notebooks_create"), "rpcids=CCqFvf")))
        .mount(&server)
        .await;

    // Envoi de fichier : enregistrement → session Scotty → contenu.
    let reg = body_of(&fixture("sources_add_file", ), "rpcids=o4cbdc").replace("vcr_test_document.txt", "page-1.jpg");
    Mock::given(method("POST")).and(query_param("rpcids", "o4cbdc")).respond_with(ResponseTemplate::new(200).set_body_string(reg)).mount(&server).await;
    let upload_url = format!("{}/upload/_/?upload_id=abc", server.uri());
    Mock::given(method("POST"))
        .and(path("/upload/_/"))
        .and(query_param("authuser", "0"))
        .and(wiremock::matchers::header("x-goog-upload-command", "start"))
        .and(body_string_contains("\"SOURCE_ID\": \"dc84ca28-2629-49ac-aec3-de45f0ec93e4\""))
        .respond_with(ResponseTemplate::new(200).insert_header("X-Goog-Upload-URL", upload_url.as_str()))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/upload/_/"))
        .and(query_param("upload_id", "abc"))
        .and(wiremock::matchers::header("x-goog-upload-offset", "0"))
        .respond_with(ResponseTemplate::new(200).set_body_string("OK: Enqueued blob bytes to spanner queue for processing."))
        .mount(&server)
        .await;

    // GET_NOTEBOOK : la source envoyée est prête.
    let nb = format!(
        ")]}}'\n\n100\n[[\"wrb.fr\",\"rLM1Ne\",{},null,null,null,\"generic\"]]\n",
        serde_json::to_string(&serde_json::json!([[
            "Cours", [[["dc84ca28-2629-49ac-aec3-de45f0ec93e4"], "page-1.jpg", [null, 10, null, null, 13], [null, 2]]],
            "afefc562-f8d1-41ec-a5d5-c197efdf52e1"
        ]]).to_string()).unwrap()
    );
    Mock::given(method("POST")).and(query_param("rpcids", "rLM1Ne")).respond_with(ResponseTemplate::new(200).set_body_string(nb)).mount(&server).await;

    // Chat, création de quiz, état, contenu.
    let chat = fixture("chat_ask");
    Mock::given(method("POST"))
        .and(path("/_/LabsTailwindUi/data/google.internal.labs.tailwind.orchestration.v1.LabsTailwindOrchestrationService/GenerateFreeFormStreamed"))
        .and(query_param("_reqid", "200000"))
        .and(query_param("bl", "boq_labs-tailwind-frontend_20260111.16_p0"))
        .and(body_string_contains("dc84ca28-2629-49ac-aec3-de45f0ec93e4"))
        .respond_with(ResponseTemplate::new(200).set_body_string(body_of(&chat, "GenerateFreeFormStreamed")))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(query_param("rpcids", "R7cb6c"))
        .and(query_param("source-path", "/notebook/afefc562-f8d1-41ec-a5d5-c197efdf52e1"))
        .respond_with(ResponseTemplate::new(200).set_body_string(body_of(&fixture("server_generate_quiz"), "rpcids=R7cb6c")))
        .mount(&server)
        .await;
    let quiz = fixture("artifacts_download_quiz");
    Mock::given(method("POST"))
        .and(query_param("rpcids", "v9rmvd"))
        .respond_with(ResponseTemplate::new(200).set_body_string(body_of(&quiz, "rpcids=v9rmvd")))
        .mount(&server)
        .await;

    let client = NotebookLmClient::with_base(SESSION.into(), "fr".into(), server.uri()).unwrap();

    let summary = client.account_summary().await.unwrap();
    assert_eq!(summary.notebooks, 12, "après renouvellement du jeton CSRF");
    assert_eq!(summary.output_language.as_deref(), Some("fr"));

    let nb_id = client.create_notebook("Cours".into()).await.unwrap();
    assert_eq!(nb_id, "afefc562-f8d1-41ec-a5d5-c197efdf52e1");

    let dir = std::env::temp_dir().join(format!("nlm-e2e-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let page = dir.join("page-1.jpg");
    std::fs::write(&page, b"\xff\xd8\xff fausse image").unwrap();
    let src = client
        .add_file_source(nb_id.clone(), page.to_string_lossy().into(), "image/jpeg".into())
        .await
        .unwrap();
    assert_eq!(src, "dc84ca28-2629-49ac-aec3-de45f0ec93e4");
    client.wait_sources_ready(nb_id.clone(), vec![src.clone()], 10).await.unwrap();

    let answer = client.ask(nb_id.clone(), "De quoi parle ce cours ?".into()).await.unwrap();
    assert!(answer.starts_with("This notebook is about"));

    let quiz_id = client.create_quiz(nb_id.clone(), Some("En français".into()), 2, 2).await.unwrap();
    assert_eq!(quiz_id, "33dc9431-cd17-4a47-aa14-1c3ef420358b");
    let json: Value = serde_json::from_str(&client.quiz_json(nb_id.clone(), quiz_id).await.unwrap()).unwrap();
    assert_eq!(json["quiz"].as_array().unwrap().len(), 10);

    // Un média hors domaines Google est refusé.
    assert!(client.download_media(format!("{}/x.m4a", server.uri()), dir.join("a.m4a").to_string_lossy().into()).await.is_err());
    // La session exportée reste réutilisable.
    assert!(client.export_session().contains("\"SID\""));
}

struct HomeRef(Arc<Home>);
impl Respond for HomeRef {
    fn respond(&self, r: &Request) -> ResponseTemplate {
        self.0.respond(r)
    }
}
