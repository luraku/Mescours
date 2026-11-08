package fr.mescours.app.engine

import android.content.Context
import fr.mescours.app.util.TarGz

/**
 * Archive Compose de la passerelle NotebookLM, envoyée à PocketDock.
 * Les sources (dossier bridge/ du dépôt) sont embarquées dans l'APK comme assets.
 */
object BridgeBundle {
    const val PORT = 8765
    const val LOCAL_URL = "http://127.0.0.1:$PORT"
    private val FILES = listOf("Dockerfile", "requirements.txt", "server.py", "prompts.py")

    fun archive(context: Context, bridgeToken: String): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        FILES.forEach { name -> files[name] = context.assets.open("bridge/$name").use { it.readBytes() } }
        files["docker-compose.yml"] = compose(bridgeToken).toByteArray()
        return TarGz.create(files)
    }

    private fun compose(token: String) = """
services:
  mescours-bridge:
    build: .
    image: mescours-bridge:latest
    container_name: mescours-bridge
    restart: unless-stopped
    ports:
      - "$PORT:$PORT"
    environment:
      BRIDGE_TOKEN: "$token"
    volumes:
      - mescours-data:/data
volumes:
  mescours-data: {}
""".trimStart()
}
