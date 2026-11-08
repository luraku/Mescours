package fr.mescours.app.engine

/** Paramètres de la passerelle NotebookLM déployée dans PocketDock. */
object BridgeBundle {
    const val PORT = 8765
    const val LOCAL_URL = "http://127.0.0.1:$PORT"
    /** Image précompilée (arm64/amd64) publiée par le workflow « Image de la passerelle ». */
    const val IMAGE = "ghcr.io/luraku/mescours-bridge:latest"
}
