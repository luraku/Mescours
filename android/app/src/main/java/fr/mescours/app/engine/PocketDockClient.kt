package fr.mescours.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client de l'API de contrôle PocketDock (http://127.0.0.1:7777/v1/), qui fait tourner
 * Docker sur le téléphone. Sert à installer et (re)démarrer la passerelle NotebookLM.
 *
 * Les formats de réponse sont lus de façon tolérante (plusieurs noms de champs possibles).
 */
class PocketDockClient(baseUrl: String, private val token: String) {

    private val base = baseUrl.trim().trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    data class Status(val running: Boolean?, val summary: String)

    data class Job(val done: Boolean, val failed: Boolean, val output: String)

    class HttpError(val code: Int, message: String) : EngineException(message)

    suspend fun status(): Status {
        val json = getJson("/status")
        val obj = json as? JsonObject
        val running = obj?.let { o ->
            o.bool("running") ?: o.bool("engineRunning")
                ?: (o["engine"] as? JsonObject)?.let { it.bool("running") ?: it.str("state")?.let(::isRunningState) }
                ?: (o.str("state") ?: o.str("status") ?: o.str("engine"))?.let(::isRunningState)
        }
        val version = obj?.let { it.str("version") ?: (it["engine"] as? JsonObject)?.str("version") }
        val state = when (running) { true -> "moteur démarré"; false -> "moteur arrêté"; null -> "état inconnu" }
        return Status(running, listOfNotNull(state, version?.let { "version $it" }).joinToString(" · "))
    }

    suspend fun startEngine() {
        try {
            send("POST", "/engine/start", null)
        } catch (e: HttpError) {
            if (e.code != 409) throw e // 409 = déjà démarré
        }
    }

    suspend fun uploadCompose(name: String, archive: ByteArray) {
        send("PUT", "/compose/$name", archive.toRequestBody("application/gzip".toMediaType()))
    }

    /** Démarre la stack ; renvoie l'identifiant de la tâche à suivre (null si l'appel est synchrone). */
    suspend fun composeUp(name: String): String? {
        val raw = send("POST", "/compose/$name/up", "{}".toRequestBody(JSON))
        val obj = runCatching { lenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return obj.str("id") ?: obj.str("jobId") ?: obj.str("job_id")
            ?: (obj["job"] as? JsonObject)?.str("id") ?: obj.str("job")
    }

    suspend fun job(id: String): Job {
        val obj = getJson("/jobs/$id") as? JsonObject ?: return Job(true, false, "")
        val state = (obj.str("status") ?: obj.str("state") ?: "").lowercase()
        val exit = obj["exitCode"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: obj["exit_code"]?.let { (it as? JsonPrimitive)?.intOrNull }
        val failed = state in setOf("failed", "error", "errored", "cancelled", "canceled") || (exit != null && exit != 0)
        val done = failed || obj.bool("done") == true || obj.bool("finished") == true || exit != null ||
            state in setOf("done", "finished", "succeeded", "success", "completed", "complete", "exited")
        val output = when (val o = obj["output"] ?: obj["log"] ?: obj["logs"] ?: obj["lines"] ?: obj["stdout"]) {
            is JsonArray -> o.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            is JsonPrimitive -> o.contentOrNull.orEmpty()
            else -> ""
        }
        return Job(done, failed, output)
    }

    private fun isRunningState(s: String) = s.lowercase().let { it.contains("run") || it == "started" || it == "up" }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

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
                when {
                    resp.isSuccessful -> raw
                    resp.code == 401 -> throw HttpError(401, "PocketDock refuse le jeton (401). Recopiez-le depuis Moteur → API de contrôle.")
                    resp.code == 503 -> throw HttpError(503, "Le moteur PocketDock est arrêté (503).")
                    else -> throw HttpError(resp.code, "PocketDock ${resp.code} : ${raw.take(300)}")
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
