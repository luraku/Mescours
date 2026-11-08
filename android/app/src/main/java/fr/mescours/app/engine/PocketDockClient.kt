package fr.mescours.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** Tâche Compose : RUNNING, SUCCEEDED ou FAILED. */
    data class Job(val done: Boolean, val failed: Boolean, val output: String, val error: String?)

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

    /** PUT /v1/compose/<nom> : corps = archive .tar.gz du dossier du projet. */
    suspend fun uploadCompose(name: String, archive: ByteArray) {
        send("PUT", "/compose/$name", archive.toRequestBody("application/gzip".toMediaType()))
    }

    /** POST /v1/compose/<nom>/up → identifiant de la tâche (202). */
    suspend fun composeUp(name: String, build: Boolean): String =
        composeAction(name, "up", """{"build":$build}""")

    /** POST /v1/compose/<nom>/pull : télécharge les images de la stack → tâche (202). */
    suspend fun composePull(name: String): String = composeAction(name, "pull", "{}")

    private suspend fun composeAction(name: String, action: String, body: String): String {
        val raw = send("POST", "/compose/$name/$action", body.toRequestBody(JSON))
        val obj = runCatching { lenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        return obj?.str("id") ?: throw EngineException("PocketDock n'a pas renvoyé de tâche pour « $action ».")
    }

    /** GET /v1/jobs/<id>?tail=N */
    suspend fun job(id: String, tail: Int = 4000): Job {
        val obj = getJson("/jobs/$id?tail=$tail") as? JsonObject
            ?: throw EngineException("Réponse PocketDock illisible (/jobs).")
        val state = obj.str("state")?.uppercase().orEmpty()
        val exit = (obj["exitCode"] as? JsonPrimitive)?.intOrNull
        val failed = state == "FAILED" || (state == "SUCCEEDED" && exit != null && exit != 0)
        return Job(
            done = state == "SUCCEEDED" || state == "FAILED",
            failed = failed,
            output = obj.str("output").orEmpty(),
            error = obj.str("error"),
        )
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun getJson(path: String): JsonElement? {
        val raw = send("GET", path, null)
        return runCatching { lenientJson.parseToJsonElement(raw) }.getOrNull()
    }

    private suspend fun send(method: String, path: String, body: okhttp3.RequestBody?): String = withContext(Dispatchers.IO) {
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
            http.newCall(request).execute().use { resp ->
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
        const val STACK_NAME = "mescours-bridge"
    }
}
