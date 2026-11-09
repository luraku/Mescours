# nlm-core

Client NotebookLM natif (Rust) utilisé par l'app Mes Cours pour fonctionner **sans
passerelle** : sous-ensemble porté de [notebooklm-py](https://github.com/teng-lin/notebooklm-py)
(MIT, © 2026 Teng Lin), protocole web `batchexecute` non officiel.

Exposé à Kotlin par [UniFFI](https://mozilla.github.io/uniffi-rs/) : objet `NotebookLmClient`
(méthodes `suspend`), erreurs `NlmException`.

| Fonction | Protocole |
|---|---|
| Session | cookies `storage_state`, jetons `SNlM0e`/`FdrFJe`, rotation `RotateCookies`, renouvellement du jeton CSRF sur refus |
| Carnets | `wXbhsf` liste, `CCqFvf` création, `rLM1Ne` sources et états |
| Photos | `o4cbdc` + envoi « resumable » (start, puis upload/finalize) |
| Texte | `hizoJc` texte intégral, chat `GenerateFreeFormStreamed` |
| Studio | `R7cb6c` quiz/audio, `gArtLc` état et URL audio, `v9rmvd` contenu du quiz |
| Réglages | `ZwVcOc`/`hT54vc` langue de sortie |

## Tests

```bash
cargo test
```

- `tests/recorded.rs` : codec et lecteurs vérifiés sur des échanges **réels** enregistrés
  (cassettes de notebooklm-py), dont la reproduction à l'octet près des corps de requête.
- `tests/end_to_end.rs` : parcours complet de l'API exportée contre un faux serveur
  (jeton périmé renouvelé, carnet, envoi de photo, chat, quiz).

## Build Android

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a -t x86_64 -P 26 -o ../android/app/build/generated/rustJniLibs build --release
cargo build --features bindgen --bin uniffi-bindgen && cargo build --lib
./target/debug/uniffi-bindgen generate --library target/debug/libnlm_core.so --language kotlin \
  --out-dir ../android/app/build/generated/uniffi/kotlin
```

Gradle lance ces étapes automatiquement (tâches `cargoNdkBuild` et `uniffiBindings`).
