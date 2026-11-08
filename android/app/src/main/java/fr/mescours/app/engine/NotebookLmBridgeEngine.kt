package fr.mescours.app.engine

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Parle à la passerelle Python (dossier `bridge/` du dépôt) qui pilote votre compte
 * NotebookLM : un carnet par cours, quiz générés par le Studio NotebookLM.
 */
class NotebookLmBridgeEngine(baseUrl: String, private val token: String) : StudyEngine {

    private val base = baseUrl.trim().trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // La génération côté NotebookLM peut prendre plusieurs minutes.
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun health(): String = withContext(Dispatchers.IO) {
        val raw = call(Request.Builder().url("$base/health").get())
        runCatching { lenientJson.parseToJsonElement(raw).jsonObject["status"]!!.jsonPrimitive.content }
            .getOrDefault(raw.take(100))
    }

    override suspend fun structureCourse(pages: List<File>, subject: String, grade: String): StructuredCourse =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("subject", subject)
                .addFormDataPart("grade", grade)
                .apply {
                    pages.forEachIndexed { i, f ->
                        val bytes = ImageUtils.compressForUpload(f)
                        addFormDataPart("files", "page-${i + 1}.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                    }
                }
                .build()
            val raw = call(Request.Builder().url("$base/courses").post(body))
            val parsed = lenientJson.decodeFromString(StructuredCourse.serializer(), raw)
            if (parsed.themes.isEmpty()) throw EngineException("NotebookLM n'a trouvé aucun thème dans ces photos.")
            parsed
        }

    override suspend fun generateQuiz(course: Course, theme: Theme, grade: String, count: Int): List<QuizQuestion> =
        withContext(Dispatchers.IO) {
            val raw = call(Request.Builder().url("$base/courses/${notebookOf(course)}/quiz").post(themeBody(course, theme, grade, count)))
            lenientJson.decodeFromString(QuizPayload.serializer(), raw).questions.sanitized()
        }

    override suspend fun generateExercises(course: Course, theme: Theme, grade: String, count: Int): List<Exercise> =
        withContext(Dispatchers.IO) {
            val raw = call(Request.Builder().url("$base/courses/${notebookOf(course)}/exercises").post(themeBody(course, theme, grade, count)))
            lenientJson.decodeFromString(ExercisesPayload.serializer(), raw).exercises
        }

    private fun notebookOf(course: Course) = course.notebookId
        ?: throw EngineException("Ce cours n'a pas de carnet NotebookLM.")

    private fun themeBody(course: Course, theme: Theme, grade: String, count: Int): RequestBody = buildJsonObject {
        put("subject", course.subject)
        put("grade", grade)
        put("theme", theme.title)
        put("count", count)
    }.toString().toRequestBody("application/json".toMediaType())

    private fun call(builder: Request.Builder): String {
        if (base.isBlank()) throw EngineException("Renseignez l'adresse de la passerelle NotebookLM dans les Réglages.")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        val request = try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw EngineException("Adresse de passerelle invalide : $base", e)
        }
        try {
            http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching {
                        lenientJson.parseToJsonElement(raw).jsonObject["detail"]!!.jsonPrimitive.content
                    }.getOrDefault(raw.take(300))
                    throw EngineException("Passerelle NotebookLM (${resp.code}) : $detail")
                }
                return raw
            }
        } catch (e: java.io.IOException) {
            throw EngineException("Passerelle injoignable ($base). Le PC est-il allumé et sur le même réseau ?", e)
        }
    }
}
