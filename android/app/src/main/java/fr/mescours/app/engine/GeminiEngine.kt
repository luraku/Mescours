package fr.mescours.app.engine

import android.util.Base64
import fr.mescours.app.model.Course
import fr.mescours.app.model.Exercise
import fr.mescours.app.model.ExercisesPayload
import fr.mescours.app.model.QuizPayload
import fr.mescours.app.model.QuizQuestion
import fr.mescours.app.model.StructuredCourse
import fr.mescours.app.model.Theme
import fr.mescours.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Appelle directement l'API Gemini avec une sortie JSON contrainte par un schéma. */
class GeminiEngine(private val apiKey: String, private val model: String) : StudyEngine {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    override suspend fun structureCourse(pages: List<File>, subject: String, grade: String): StructuredCourse {
        val parts = buildList {
            add(textPart(Prompts.structure(subject, grade)))
            pages.forEach { add(imagePart(ImageUtils.compressForUpload(it))) }
        }
        val text = generate(parts, Schemas.course)
        val parsed = lenientJson.decodeFromString(StructuredCourse.serializer(), extractJsonObject(text))
        if (parsed.themes.isEmpty()) throw EngineException("Aucun thème n'a été trouvé dans ces photos.")
        return parsed.copy(notebookId = null)
    }

    override suspend fun generateQuiz(course: Course, theme: Theme, grade: String, count: Int): List<QuizQuestion> {
        val text = generate(
            listOf(textPart(Prompts.themeContext(course, theme)), textPart(Prompts.quiz(course.subject, grade, theme.title, count))),
            Schemas.quiz,
        )
        return lenientJson.decodeFromString(QuizPayload.serializer(), extractJsonObject(text)).questions.sanitized()
    }

    override suspend fun generateExercises(course: Course, theme: Theme, grade: String, count: Int): List<Exercise> {
        val text = generate(
            listOf(textPart(Prompts.themeContext(course, theme)), textPart(Prompts.exercises(course.subject, grade, theme.title, count))),
            Schemas.exercises,
        )
        return lenientJson.decodeFromString(ExercisesPayload.serializer(), extractJsonObject(text)).exercises
    }

    private fun textPart(text: String) = buildJsonObject { put("text", text) }

    private fun imagePart(jpeg: ByteArray) = buildJsonObject {
        putJsonObject("inline_data") {
            put("mime_type", "image/jpeg")
            put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        }
    }

    private suspend fun generate(parts: List<JsonObject>, schema: JsonElement): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw EngineException("Ajoutez votre clé API Gemini dans les Réglages.")
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(parts))
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
                put("temperature", 0.4)
            }
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    lenientJson.parseToJsonElement(raw).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                }.getOrDefault(raw.take(300))
                throw EngineException("Gemini a répondu ${resp.code} : $msg")
            }
            val root = lenientJson.parseToJsonElement(raw).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw EngineException("Gemini n'a renvoyé aucune réponse (contenu bloqué ?).")
            candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
                ?.takeIf { it.isNotBlank() }
                ?: throw EngineException("Réponse Gemini vide.")
        }
    }
}

/** Schémas de réponse (sous-ensemble OpenAPI accepté par Gemini). */
private object Schemas {
    private fun str() = buildJsonObject { put("type", "STRING") }
    private fun strArray() = buildJsonObject { put("type", "ARRAY"); put("items", str()) }
    private fun obj(required: List<String>, vararg props: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "OBJECT")
        put("properties", JsonObject(props.toMap()))
        putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }
    private fun arrayOf(item: JsonElement) = buildJsonObject { put("type", "ARRAY"); put("items", item) }

    val course: JsonElement = obj(
        listOf("title", "transcript", "themes"),
        "title" to str(),
        "transcript" to str(),
        "themes" to arrayOf(
            obj(
                listOf("title", "summary", "keyPoints"),
                "title" to str(),
                "summary" to str(),
                "keyPoints" to strArray(),
                "vocabulary" to arrayOf(obj(listOf("term", "definition"), "term" to str(), "definition" to str())),
                "examples" to strArray(),
            )
        ),
    )

    val quiz: JsonElement = obj(
        listOf("questions"),
        "questions" to arrayOf(
            obj(
                listOf("question", "options", "correctIndex", "explanation"),
                "question" to str(),
                "options" to strArray(),
                "correctIndex" to buildJsonObject { put("type", "INTEGER") },
                "explanation" to str(),
                "hint" to str(),
            )
        ),
    )

    val exercises: JsonElement = obj(
        listOf("exercises"),
        "exercises" to arrayOf(
            obj(
                listOf("statement", "solution", "difficulty"),
                "statement" to str(),
                "hint" to str(),
                "solution" to str(),
                "difficulty" to buildJsonObject {
                    put("type", "STRING")
                    putJsonArray("enum") { listOf("facile", "moyen", "difficile").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                },
            )
        ),
    )
}
