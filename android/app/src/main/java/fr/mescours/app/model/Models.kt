package fr.mescours.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class Child(
    val id: String = newId(),
    val name: String,
    val grade: String,
)

/** Un cours photographié puis restructuré par l'IA en plusieurs thèmes (fiches). */
@Serializable
data class Course(
    val id: String = newId(),
    val childId: String,
    val subject: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val pagePaths: List<String> = emptyList(),
    /** Transcription intégrale (utile au moteur Gemini pour générer quiz/exercices). */
    val transcript: String = "",
    val themes: List<Theme> = emptyList(),
    /** Identifiant du carnet NotebookLM si le cours a été créé via la passerelle. */
    val notebookId: String? = null,
    val engine: String = "",
)

@Serializable
data class Theme(
    val id: String = newId(),
    val title: String,
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val vocabulary: List<Term> = emptyList(),
    val examples: List<String> = emptyList(),
    val quiz: List<QuizQuestion> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
)

@Serializable
data class Term(val term: String, val definition: String)

@Serializable
data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String = "",
    val hint: String = "",
)

@Serializable
data class Exercise(
    val statement: String,
    val hint: String = "",
    val solution: String,
    val difficulty: String = "moyen",
)

@Serializable
data class Library(
    val children: List<Child> = emptyList(),
    val courses: List<Course> = emptyList(),
)

/** Résultat de l'analyse d'un cours, commun aux deux moteurs. */
@Serializable
data class StructuredCourse(
    val title: String,
    val transcript: String = "",
    val themes: List<Theme> = emptyList(),
    val notebookId: String? = null,
)

@Serializable
data class QuizPayload(val questions: List<QuizQuestion> = emptyList())

@Serializable
data class ExercisesPayload(val exercises: List<Exercise> = emptyList())

val SUBJECTS = listOf(
    "Français", "Mathématiques", "Histoire-Géographie", "SVT", "Physique-Chimie",
    "Anglais", "Espagnol", "Allemand", "Technologie", "EMC", "Sciences", "Philosophie",
)

val GRADES = listOf(
    "CP", "CE1", "CE2", "CM1", "CM2", "6e", "5e", "4e", "3e", "Seconde", "Première", "Terminale",
)
