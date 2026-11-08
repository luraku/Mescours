package fr.mescours.app.engine

import fr.mescours.app.model.Course
import fr.mescours.app.model.Exercise
import fr.mescours.app.model.QuizQuestion
import fr.mescours.app.model.StructuredCourse
import fr.mescours.app.model.Theme
import kotlinx.serialization.json.Json
import java.io.File

/** Moteur IA : Gemini (API officielle) ou NotebookLM (via la passerelle). */
interface StudyEngine {
    /** Lit les photos du cours et le découpe en thèmes (fiches de révision). */
    suspend fun structureCourse(pages: List<File>, subject: String, grade: String): StructuredCourse

    suspend fun generateQuiz(course: Course, theme: Theme, grade: String, count: Int): List<QuizQuestion>

    suspend fun generateExercises(course: Course, theme: Theme, grade: String, count: Int): List<Exercise>
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Extrait l'objet JSON d'une réponse qui peut contenir du texte ou des balises ```json. */
internal fun extractJsonObject(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) throw EngineException("Réponse de l'IA illisible (pas de JSON).")
    return text.substring(start, end + 1)
}

/** Consignes communes, partagées par les deux moteurs (et reprises côté passerelle). */
object Prompts {
    fun structure(subject: String, grade: String) = """
Tu es un professeur bienveillant qui prépare des fiches de révision pour un élève de $grade en $subject.
Les images sont des photos de cahier ou de manuel (écriture manuscrite possible).
1. Transcris fidèlement tout le contenu du cours (champ "transcript"), sans rien inventer.
2. Donne un titre court au cours (champ "title").
3. Découpe le cours en thèmes cohérents (2 à 6 en général). Pour chaque thème :
   - "title" : titre clair,
   - "summary" : résumé de 3 à 6 phrases adapté au niveau $grade,
   - "keyPoints" : les points essentiels à retenir (phrases courtes),
   - "vocabulary" : mots ou notions clés avec leur définition ("term", "definition"),
   - "examples" : exemples ou applications tirés du cours.
Reste strictement fidèle au cours ; écris en français.
""".trimIndent()

    fun quiz(subject: String, grade: String, themeTitle: String, count: Int) = """
Crée un quiz de $count questions à choix multiples (4 propositions, une seule bonne réponse)
pour un élève de $grade en $subject, uniquement sur le thème « $themeTitle » du cours fourni.
Varie les types de questions (définitions, compréhension, application). Pour chaque question :
"question", "options" (4 propositions), "correctIndex" (0 à 3), "explanation" (pourquoi c'est la bonne réponse),
"hint" (un indice qui ne donne pas la réponse). Écris en français, niveau adapté à $grade.
""".trimIndent()

    fun exercises(subject: String, grade: String, themeTitle: String, count: Int) = """
Crée $count exercices progressifs (du plus facile au plus difficile) pour un élève de $grade en $subject,
uniquement sur le thème « $themeTitle » du cours fourni. Pour chaque exercice :
"statement" (énoncé complet), "hint" (coup de pouce), "solution" (correction détaillée, étape par étape),
"difficulty" ("facile", "moyen" ou "difficile"). Écris en français, niveau adapté à $grade.
""".trimIndent()

    fun themeContext(course: Course, theme: Theme): String = buildString {
        appendLine("COURS : ${course.title} (${course.subject})")
        appendLine("THÈME : ${theme.title}")
        appendLine("Résumé : ${theme.summary}")
        if (theme.keyPoints.isNotEmpty()) appendLine("Points clés :\n- " + theme.keyPoints.joinToString("\n- "))
        if (theme.vocabulary.isNotEmpty()) appendLine("Vocabulaire :\n- " + theme.vocabulary.joinToString("\n- ") { "${it.term} : ${it.definition}" })
        if (theme.examples.isNotEmpty()) appendLine("Exemples :\n- " + theme.examples.joinToString("\n- "))
        if (course.transcript.isNotBlank()) {
            appendLine()
            appendLine("TRANSCRIPTION COMPLÈTE DU COURS :")
            appendLine(course.transcript)
        }
    }
}

/** Nettoie un quiz : bornes de correctIndex, propositions vides. */
internal fun List<QuizQuestion>.sanitized(): List<QuizQuestion> = mapNotNull { q ->
    val opts = q.options.filter { it.isNotBlank() }
    if (q.question.isBlank() || opts.size < 2) null
    else q.copy(options = opts, correctIndex = q.correctIndex.coerceIn(0, opts.lastIndex))
}
