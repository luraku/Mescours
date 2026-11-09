package fr.mescours.app.model

import kotlinx.serialization.Serializable

/** Progression d'un enfant : XP, série de jours, statistiques et badges débloqués. */
@Serializable
data class ChildProgress(
    val xp: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** Dernier jour d'activité (nombre de jours depuis le 1/1/1970), -1 = jamais. */
    val lastActiveDay: Long = -1,
    val quizzesCompleted: Int = 0,
    val perfectQuizzes: Int = 0,
    val correctAnswers: Int = 0,
    val coursesAdded: Int = 0,
    /** Exercices marqués « réussi » (clé stable par exercice). */
    val exercisesDone: Set<String> = emptySet(),
    /** Meilleur score (%) par thème. */
    val bestScores: Map<String, Int> = emptyMap(),
    /** Badge débloqué → date (ms). */
    val badges: Map<String, Long> = emptyMap(),
)

data class Level(val number: Int, val title: String, val emoji: String, val minXp: Int, val nextXp: Int?) {
    fun progress(xp: Int): Float = nextXp?.let { ((xp - minXp).toFloat() / (it - minXp)).coerceIn(0f, 1f) } ?: 1f
}

data class Badge(
    val id: String,
    val emoji: String,
    val title: String,
    val description: String,
    val unlocked: (p: ChildProgress, subjects: Int) -> Boolean,
)

/** Ce que l'enfant vient de gagner, affiché dans la fenêtre de récompense. */
data class Reward(
    val childName: String,
    val headline: String,
    val xp: Int,
    val newBadges: List<Badge>,
    val levelUp: Level?,
    val streak: Int,
    val streakIncreased: Boolean,
)

sealed interface ProgressEvent {
    data class QuizFinished(val themeId: String, val correct: Int, val total: Int) : ProgressEvent
    data class ExerciseDone(val key: String) : ProgressEvent
    data object CourseAdded : ProgressEvent
}

object Gamification {
    const val XP_COURSE = 20
    const val XP_QUIZ = 10
    const val XP_PER_CORRECT = 3
    const val XP_PERFECT_BONUS = 20
    const val XP_EXERCISE = 8

    private val LEVELS = listOf(
        Triple(0, "Débutant", "🌱"),
        Triple(100, "Apprenti", "📘"),
        Triple(250, "Curieux", "🔍"),
        Triple(500, "Studieux", "✏️"),
        Triple(850, "Malin", "💡"),
        Triple(1300, "Expert", "🎯"),
        Triple(1900, "Champion", "🏅"),
        Triple(2600, "As des révisions", "🚀"),
        Triple(3500, "Génie", "🧠"),
        Triple(4600, "Légende", "👑"),
    )

    fun levelOf(xp: Int): Level {
        val idx = LEVELS.indexOfLast { xp >= it.first }.coerceAtLeast(0)
        val (min, title, emoji) = LEVELS[idx]
        return Level(idx + 1, title, emoji, min, LEVELS.getOrNull(idx + 1)?.first)
    }

    val BADGES: List<Badge> = listOf(
        Badge("first_course", "📸", "Premier cours", "Ajouter un premier cours") { p, _ -> p.coursesAdded >= 1 },
        Badge("first_quiz", "🎬", "C'est parti !", "Terminer un premier quiz") { p, _ -> p.quizzesCompleted >= 1 },
        Badge("perfect", "💯", "Sans faute", "Réussir un quiz à 100 %") { p, _ -> p.perfectQuizzes >= 1 },
        Badge("perfect3", "🌟", "Triple sans faute", "Trois quiz à 100 %") { p, _ -> p.perfectQuizzes >= 3 },
        Badge("quiz5", "🧩", "Joueur", "Terminer 5 quiz") { p, _ -> p.quizzesCompleted >= 5 },
        Badge("quiz25", "🏆", "Marathonien", "Terminer 25 quiz") { p, _ -> p.quizzesCompleted >= 25 },
        Badge("answers100", "✅", "Cent bonnes réponses", "100 bonnes réponses au total") { p, _ -> p.correctAnswers >= 100 },
        Badge("exercise1", "✍️", "Au travail", "Réussir un premier exercice") { p, _ -> p.exercisesDone.isNotEmpty() },
        Badge("exercise10", "📝", "Bûcheur", "Réussir 10 exercices") { p, _ -> p.exercisesDone.size >= 10 },
        Badge("streak3", "🔥", "En feu", "3 jours de révision d'affilée") { p, _ -> p.bestStreak >= 3 },
        Badge("streak7", "⚡", "Semaine parfaite", "7 jours de révision d'affilée") { p, _ -> p.bestStreak >= 7 },
        Badge("mastery", "⭐", "Maîtrise", "Obtenir 80 % ou plus sur un thème") { p, _ -> p.bestScores.values.any { it >= 80 } },
        Badge("mastery5", "🎓", "Grand maître", "80 % ou plus sur 5 thèmes") { p, _ -> p.bestScores.values.count { it >= 80 } >= 5 },
        Badge("subjects3", "🌈", "Touche-à-tout", "Des cours dans 3 matières") { _, s -> s >= 3 },
        Badge("courses10", "📚", "Bibliothécaire", "Ajouter 10 cours") { p, _ -> p.coursesAdded >= 10 },
        Badge("xp1000", "💎", "Mille XP", "Atteindre 1 000 XP") { p, _ -> p.xp >= 1000 },
    )

    /**
     * Applique un événement : XP, série de jours, statistiques, nouveaux badges.
     * [subjects] = nombre de matières différentes de l'enfant ; [today] = jour courant (epoch day).
     */
    fun apply(
        p: ChildProgress, event: ProgressEvent, subjects: Int, today: Long, now: Long = System.currentTimeMillis(),
    ): Pair<ChildProgress, Reward?> {
        var next = p
        var gained = 0
        var headline = ""
        when (event) {
            is ProgressEvent.CourseAdded -> {
                gained = XP_COURSE
                next = next.copy(coursesAdded = next.coursesAdded + 1)
                headline = "Nouveau cours rangé !"
            }
            is ProgressEvent.QuizFinished -> {
                val total = event.total.coerceAtLeast(1)
                val pct = event.correct * 100 / total
                val perfect = event.correct == event.total && event.total > 0
                gained = XP_QUIZ + event.correct * XP_PER_CORRECT + if (perfect) XP_PERFECT_BONUS else 0
                val best = maxOf(next.bestScores[event.themeId] ?: 0, pct)
                next = next.copy(
                    quizzesCompleted = next.quizzesCompleted + 1,
                    perfectQuizzes = next.perfectQuizzes + if (perfect) 1 else 0,
                    correctAnswers = next.correctAnswers + event.correct,
                    bestScores = next.bestScores + (event.themeId to best),
                )
                headline = when {
                    perfect -> "Sans faute, bravo !"
                    pct >= 80 -> "Excellent travail !"
                    pct >= 50 -> "Bien joué !"
                    else -> "Continue, tu progresses !"
                }
            }
            is ProgressEvent.ExerciseDone -> {
                if (event.key in next.exercisesDone) return p to null
                gained = XP_EXERCISE
                next = next.copy(exercisesDone = next.exercisesDone + event.key)
                headline = "Exercice réussi !"
            }
        }

        // Série de jours consécutifs.
        val streakBefore = next.streak
        val streak = when (next.lastActiveDay) {
            today -> next.streak.coerceAtLeast(1)
            today - 1 -> next.streak + 1
            else -> 1
        }
        next = next.copy(streak = streak, bestStreak = maxOf(next.bestStreak, streak), lastActiveDay = today)

        val levelBefore = levelOf(p.xp)
        next = next.copy(xp = next.xp + gained)
        val newBadges = BADGES.filter { it.id !in next.badges && it.unlocked(next, subjects) }
        if (newBadges.isNotEmpty()) next = next.copy(badges = next.badges + newBadges.associate { it.id to now })
        val levelAfter = levelOf(next.xp)

        val reward = Reward(
            childName = "",
            headline = headline,
            xp = gained,
            newBadges = newBadges,
            levelUp = levelAfter.takeIf { it.number > levelBefore.number },
            streak = streak,
            streakIncreased = streak > streakBefore,
        )
        return next to reward
    }
}
