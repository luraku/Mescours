package fr.mescours.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client de l'API de contrôle PocketDock (https://github.com/luraku/pocketdock,
 * docs/control-api.md) : http://127.0.0.1:7777/v1, jeton en `Authorization: Bearer`.
 * Sert à installer et (re)démarrer la passerelle NotebookLM dans la VM Docker du téléphone.
 * Les ports publiés par la stack sont redirigés par PocketDock vers 127.0.0.1 du téléphone.
 */
class PocketDockClient(baseUrl: String, private val token: String) {

    private val base = baseUrl.trim().trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Téléchargement d'image dans la VM émulée : peut prendre de longues minutes. */
    private val slowHttp = http.newBuilder().readTimeout(40, TimeUnit.MINUTES).build()

    /** État du moteur : STOPPED, PREPARING, BOOTING, RUNNING, STOPPING ou ERROR. */
    data class Status(val state: String, val message: String, val progress: Int?, val version: String?) {
        val running get() = state == "RUNNING"
        val starting get() = state == "PREPARING" || state == "BOOTING"
        val summary get() = listOfNotNull(
            when (state) {
                "RUNNING" -> "moteur démarré"
                "STOPPED" -> "moteur arrêté"
                "PREPARING", "BOOTING" -> "moteur en cours de démarrage"
                "STOPPING" -> "moteur en cours d'arrêt"
                "ERROR" -> "moteur en erreur" + message.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                else -> "état $state"
            },
            version?.let { "PocketDock $it" },
        ).joinToString(" · ")
    }

    class HttpError(val code: Int, message: String) : EngineException(message)

    /** GET /v1/status */
    suspend fun status(): Status {
        val o = getJson("/status") as? JsonObject ?: throw EngineException("Réponse PocketDock illisible (/status).")
        val engine = o["engine"] as? JsonObject
        return Status(
            state = engine?.str("state")?.uppercase() ?: "UNKNOWN",
            message = engine?.str("message").orEmpty(),
            progress = (engine?.get("progress") as? JsonPrimitive)?.intOrNull,
            version = o.str("version"),
        )
    }

    /** POST /v1/engine/start (202, à suivre avec status()). */
    suspend fun startEngine() {
        send("POST", "/engine/start", null)
    }

    /** POST /v1/images/pull : télécharge (ou met à jour) l'image ; synchrone, peut être long. */
    suspend fun pullImage(ref: String) {
        send("POST", "/images/pull", jsonBody("ref" to ref), slow = true)
    }

    /** DELETE /v1/docker/containers/<nom>?force=true ; sans effet si le conteneur n'existe pas. */
    suspend fun removeContainer(name: String) {
        try {
            send("DELETE", "/docker/containers/$name?force=true", null)
        } catch (e: HttpError) {
            if (e.code != 404) throw e
        }
    }

    /** POST /v1/docker/containers/<nom>/start ; renvoie false si le conteneur n'existe pas. */
    suspend fun startContainer(name: String): Boolean = try {
        send("POST", "/docker/containers/$name/start", null)
        true
    } catch (e: HttpError) {
        when (e.code) {
            304 -> true // déjà démarré
            404 -> false
            else -> throw e
        }
    }

    /** POST /v1/containers/run : crée et démarre (télécharge l'image si besoin), 201. */
    suspend fun runContainer(
        image: String, name: String, ports: List<String>, env: List<String>, volumes: List<String>,
    ) {
        val body = buildJsonObject {
            put("image", image)
            put("name", name)
            put("ports", JsonArray(ports.map { JsonPrimitive(it) }))
            put("env", JsonArray(env.map { JsonPrimitive(it) }))
            put("volumes", JsonArray(volumes.map { JsonPrimitive(it) }))
            put("restartAlways", true)
        }
        send("POST", "/containers/run", body.toString().toRequestBody(JSON), slow = true)
    }

    /** Passerelle déjà déployée : jeton (variable BRIDGE_TOKEN) et port publié sur le téléphone. */
    data class ExistingBridge(val name: String, val token: String, val port: Int)

    /**
     * Cherche le conteneur de la passerelle (nom ou image « mescours-bridge », ou port interne 8765)
     * via l'API Docker de PocketDock, puis lit son jeton et son port publié.
     */
    suspend fun findBridge(): ExistingBridge? {
        val list = getJson("/docker/containers/json?all=1") as? JsonArray ?: return null
        val candidates = list.mapNotNull { it as? JsonObject }
        val match = candidates.firstOrNull { c ->
            val names = (c["Names"] as? JsonArray)?.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
            "mescours-bridge" in names || "mescours-bridge" in c.str("Image").orEmpty()
        } ?: candidates.firstOrNull { c ->
            (c["Ports"] as? JsonArray)?.any { ((it as? JsonObject)?.get("PrivatePort") as? JsonPrimitive)?.intOrNull == 8765 } == true
        } ?: return null
        val id = match.str("Id") ?: return null
        val inspect = getJson("/docker/containers/$id/json") as? JsonObject ?: return null
        val env = ((inspect["Config"] as? JsonObject)?.get("Env") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val token = env.firstOrNull { it.startsWith("BRIDGE_TOKEN=") }?.substringAfter("=").orEmpty()
        val bindings = ((inspect["HostConfig"] as? JsonObject)?.get("PortBindings") as? JsonObject)
        val hostPort = (bindings?.get("8765/tcp") as? JsonArray)?.firstOrNull()
            ?.let { ((it as? JsonObject)?.get("HostPort") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        val name = (inspect.str("Name") ?: id.take(12)).removePrefix("/")
        return ExistingBridge(name, token, hostPort ?: 8765)
    }

    private fun jsonBody(vararg pairs: Pair<String, String>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString().toRequestBody(JSON)

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun getJson(path: String): JsonElement? {
        val raw = send("GET", path, null)
        return runCatching { lenientJson.parseToJsonElement(raw) }.getOrNull()
    }

    private suspend fun send(method: String, path: String, body: okhttp3.RequestBody?, slow: Boolean = false): String = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw EngineException("Collez le jeton PocketDock dans les Réglages (Moteur → API de contrôle).")
        val request = try {
            Request.Builder().url(base + path)
                .header("Authorization", "Bearer $token")
                .method(method, body ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null)
                .build()
        } catch (e: IllegalArgumentException) {
            throw EngineException("Adresse PocketDock invalide : $base", e)
        }
        try {
            (if (slow) slowHttp else http).newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val apiError = runCatching {
                    (lenientJson.parseToJsonElement(raw) as JsonObject).str("error")
                }.getOrNull() ?: raw.take(300)
                when {
                    resp.isSuccessful -> raw
                    resp.code == 401 -> throw HttpError(401, "PocketDock refuse le jeton (401). Recopiez-le depuis Moteur → API de contrôle.")
                    resp.code == 503 -> throw HttpError(503, "Le moteur PocketDock n'est pas démarré (503) : $apiError")
                    else -> throw HttpError(resp.code, "PocketDock ${resp.code} : $apiError")
                }
            }
        } catch (e: java.io.IOException) {
            throw EngineException("PocketDock injoignable ($base). L'app est-elle lancée et l'API de contrôle activée ?", e)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        const val DEFAULT_URL = "http://127.0.0.1:7777"
        const val CONTAINER_NAME = "mescours-bridge"
    }
}
