package fr.mescours.app.engine

import fr.mescours.app.data.SecureSessionStore
import fr.mescours.app.model.Course
import fr.mescours.app.model.Exercise
import fr.mescours.app.model.ExercisesPayload
import fr.mescours.app.model.QuizPayload
import fr.mescours.app.model.QuizQuestion
import fr.mescours.app.model.StructuredCourse
import fr.mescours.app.model.Theme
import fr.mescours.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.nlm_core.NlmException
import uniffi.nlm_core.NotebookLmClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moteur NotebookLM **intégré** : l'app parle directement à NotebookLM grâce à la
 * bibliothèque native nlm-core (Rust), sans passerelle ni PocketDock.
 */
class EmbeddedNotebookLmEngine(
    private val session: SecureSessionStore,
    private val cacheDir: File,
    private val language: String = "fr",
) : StudyEngine {

    @Volatile private var cached: NotebookLmClient? = null

    /** À appeler quand une nouvelle session Google a été enregistrée. */
    fun reset() { cached = null }

    fun hasSession(): Boolean = session.exists()

    private fun client(): NotebookLmClient {
        cached?.let { return it }
        val json = session.load()
            ?: throw EngineException("Connectez votre compte Google (Réglages → NotebookLM intégré).")
        val c = try {
            NotebookLmClient(json, language)
        } catch (e: NlmException) {
            throw EngineException(e.readable(), e)
        } catch (e: Throwable) { // UnsatisfiedLinkError : bibliothèque native absente
            throw EngineException("Bibliothèque NotebookLM intégrée indisponible : ${e.message}", e)
        }
        cached = c
        return c
    }

    /** Exécute un appel natif, enregistre la session renouvelée et traduit les erreurs. */
    private suspend fun <T> call(block: suspend (NotebookLmClient) -> T): T {
        val c = client()
        try {
            return block(c)
        } catch (e: NlmException) {
            if (e is NlmException.AuthExpired) cached = null
            throw EngineException(e.readable(), e)
        } finally {
            runCatching { session.save(c.exportSession()) }
        }
    }

    /** Vérifie la session : nombre de carnets du compte. */
    suspend fun accountStatus(): String = call { c ->
        val s = c.accountSummary()
        "compte connecté (${s.notebooks} carnets, langue ${s.outputLanguage ?: "par défaut"})"
    }

    override suspend fun structureCourse(pages: List<File>, subject: String, grade: String): StructuredCourse = call { c ->
        runCatching { c.ensureOutputLanguage(language) }
        val title = "Mes Cours · $subject · " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())
        val notebookId = c.createNotebook(title)
        val tmpDir = File(cacheDir, "nlm-upload").apply { mkdirs() }
        val sourceIds = pages.mapIndexed { i, page ->
            val tmp = File(tmpDir, "page-${i + 1}.jpg")
            withContext(Dispatchers.IO) { tmp.writeBytes(ImageUtils.compressForUpload(page)) }
            try {
                c.addFileSource(notebookId, tmp.path, "image/jpeg")
            } finally {
                tmp.delete()
            }
        }
        c.waitSourcesReady(notebookId, sourceIds, 300u)
        val transcript = sourceIds.mapNotNull { id -> runCatching { c.sourceText(notebookId, id) }.getOrNull() }
            .joinToString("\n\n")
        val answer = c.ask(notebookId, NotebookPrompts.structure(subject, grade))
        val parsed = lenientJson.decodeFromString(StructuredCourse.serializer(), extractNotebookJson(answer))
        if (parsed.themes.isEmpty()) throw EngineException("NotebookLM n'a trouvé aucun thème dans ces photos.")
        parsed.copy(transcript = transcript, notebookId = notebookId)
    }

    override suspend fun generateQuiz(course: Course, theme: Theme, grade: String, count: Int): List<QuizQuestion> {
        val nb = notebookOf(course)
        // 1) Générateur de quiz du Studio NotebookLM.
        val studio = runCatching {
            call { c ->
                runCatching { c.ensureOutputLanguage(language) }
                val quantity: UByte = when { count <= 5 -> 1u; count <= 10 -> 2u; else -> 3u }
                val id = c.createQuiz(nb, NotebookPrompts.quizInstructions(course.subject, grade, theme.title), quantity, 2u)
                awaitArtifact(c, nb, id, timeoutMs = 7 * 60_000L)
                convertStudioQuiz(c.quizJson(nb, id))
            }
        }.getOrNull()
        if (!studio.isNullOrEmpty()) return studio
        // 2) Repli : quiz demandé au chat du carnet, en JSON.
        val answer = call { it.ask(nb, NotebookPrompts.quizJson(course.subject, grade, theme.title, count)) }
        return lenientJson.decodeFromString(QuizPayload.serializer(), extractNotebookJson(answer)).questions.sanitized()
    }

    override suspend fun generateExercises(course: Course, theme: Theme, grade: String, count: Int): List<Exercise> {
        val answer = call { it.ask(notebookOf(course), NotebookPrompts.exercisesJson(course.subject, grade, theme.title, count)) }
        return lenientJson.decodeFromString(ExercisesPayload.serializer(), extractNotebookJson(answer)).exercises
    }

    // ------------------------------------------------------------ résumé audio

    suspend fun startAudio(course: Course, theme: Theme, grade: String, short: Boolean): String = call { c ->
        runCatching { c.ensureOutputLanguage(language) }
        c.createAudio(notebookOf(course), NotebookPrompts.audioInstructions(course.subject, grade, theme.title), language, short)
    }

    /** « pending », « completed » (URL du média) ou « failed ». */
    suspend fun audioState(course: Course, taskId: String): Pair<String, String?> = call { c ->
        val s = c.artifactState(notebookOf(course), taskId)
        when (s.status) {
            "completed" -> "completed" to s.audioUrl
            "failed" -> "failed" to "génération échouée"
            else -> "pending" to null // not_found juste après la création : encore en cours
        }
    }

    suspend fun downloadAudio(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        call { it.downloadMedia(url, dest.path) }
    }

    // ------------------------------------------------------------ interne

    private fun notebookOf(course: Course) = course.notebookId
        ?: throw EngineException("Ce cours n'a pas de carnet NotebookLM.")

    private suspend fun awaitArtifact(c: NotebookLmClient, nb: String, id: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (c.artifactState(nb, id).status) {
                "completed" -> return
                "failed" -> throw EngineException("NotebookLM n'a pas pu générer le quiz.")
            }
            delay(5_000)
        }
        throw EngineException("Le quiz NotebookLM n'est pas prêt à temps.")
    }

    /** Format Studio `{quiz:[{question, answerOptions[{text,isCorrect,rationale}], hint}]}` → app. */
    private fun convertStudioQuiz(json: String): List<QuizQuestion> {
        val root = lenientJson.parseToJsonElement(json).jsonObject
        return root["quiz"]?.jsonArray.orEmpty().mapNotNull { el ->
            val q = el as? JsonObject ?: return@mapNotNull null
            val options = q["answerOptions"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
            if (options.size < 2) return@mapNotNull null
            val correct = options.indexOfFirst { it["isCorrect"]?.jsonPrimitive?.booleanOrNull == true }.coerceAtLeast(0)
            QuizQuestion(
                question = q["question"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                options = options.map { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() },
                correctIndex = correct,
                explanation = options[correct]["rationale"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                hint = q["hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.sanitized()
    }
}

private val CITATION = Regex("""\s?\[\d+(?:\s*[-–,]\s*\d+)*]""")

/** JSON d'une réponse de chat NotebookLM (balises de code et citations [1] retirées). */
internal fun extractNotebookJson(answer: String): String = extractJsonObject(CITATION.replace(answer, ""))

internal fun NlmException.readable(): String = when (this) {
    is NlmException.AuthExpired -> "${message.orEmpty()}. Reconnectez-vous : Réglages → NotebookLM intégré."
    is NlmException.RateLimited -> "${message.orEmpty()}. Réessayez plus tard."
    else -> message ?: toString()
}

/** Consignes NotebookLM (identiques à celles de la passerelle, bridge/prompts.py). */
object NotebookPrompts {
    private const val MATH = "Pour toute formule, utilise la notation LaTeX entre \$ (ex. \$\\frac{2}{3}\$, \$3 \\times 4\$, \$x^{2}\$) ; reste simple et adapté à un enfant."
    private const val JSON_ONLY = "Réponds UNIQUEMENT avec un objet JSON valide, sans texte avant ou après, sans balise de code et sans numéros de citation."

    fun structure(subject: String, grade: String) = """
Tu es un professeur bienveillant qui prépare des fiches de révision pour un élève de $grade en $subject.
Les sources de ce carnet sont des photos du cours (cahier ou manuel).
Découpe le cours en thèmes cohérents (2 à 6 en général). Pour chaque thème donne :
"title" (titre clair), "summary" (résumé de 3 à 6 phrases adapté au niveau $grade),
"keyPoints" (liste des points essentiels), "vocabulary" (liste d'objets {"term", "definition"}),
"examples" (exemples tirés du cours). Reste strictement fidèle au cours, en français.
$MATH
$JSON_ONLY
Format : {"title": "titre court du cours", "themes": [{"title": "", "summary": "", "keyPoints": [], "vocabulary": [{"term": "", "definition": ""}], "examples": []}]}
""".trim()

    fun quizInstructions(subject: String, grade: String, theme: String) =
        "Écris tout le quiz EN FRANÇAIS (questions, réponses, explications, indices). " +
            "Quiz pour un élève de $grade en $subject, uniquement sur le thème « $theme ». " +
            "Questions variées et adaptées à son niveau. $MATH"

    fun quizJson(subject: String, grade: String, theme: String, count: Int) = """
Crée un quiz de $count questions à choix multiples (4 propositions, une seule bonne réponse)
pour un élève de $grade en $subject, uniquement sur le thème « $theme » de ce cours.
$MATH
$JSON_ONLY
Format : {"questions": [{"question": "", "options": ["", "", "", ""], "correctIndex": 0, "explanation": "", "hint": ""}]}
""".trim()

    fun exercisesJson(subject: String, grade: String, theme: String, count: Int) = """
Crée $count exercices progressifs (du plus facile au plus difficile) pour un élève de $grade en $subject,
uniquement sur le thème « $theme » de ce cours, avec une correction détaillée étape par étape.
$MATH
$JSON_ONLY
Format : {"exercises": [{"statement": "", "hint": "", "solution": "", "difficulty": "facile|moyen|difficile"}]}
""".trim()

    fun audioInstructions(subject: String, grade: String, theme: String) =
        "Résumé audio EN FRANÇAIS pour un élève de $grade en $subject" +
            (if (theme.isNotBlank()) ", centré sur le thème « $theme »" else "") +
            ". Ton chaleureux et encourageant, phrases simples, exemples concrets du cours, " +
            "et récapitulatif des points essentiels à retenir à la fin."
}
