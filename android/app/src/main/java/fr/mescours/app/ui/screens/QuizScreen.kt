package fr.mescours.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.mescours.app.ui.AppViewModel

private val Good = Color(0xFF2F9E44)
private val Bad = Color(0xFFE03131)

@Composable
fun QuizScreen(vm: AppViewModel, courseId: String, themeId: String, onBack: () -> Unit) {
    val library by vm.library.collectAsState()
    val busy by vm.busy.collectAsState()
    val course = library.courses.find { it.id == courseId }
    val theme = course?.themes?.find { it.id == themeId }
    if (course == null || theme == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val quiz = theme.quiz

    // L'état de la partie est remis à zéro quand un nouveau quiz est généré.
    val quizKey = quiz.hashCode()
    var index by rememberSaveable(quizKey) { mutableIntStateOf(0) }
    var selected by rememberSaveable(quizKey) { mutableStateOf<Int?>(null) }
    var score by rememberSaveable(quizKey) { mutableIntStateOf(0) }
    var showHint by rememberSaveable(quizKey, index) { mutableStateOf(false) }

    Scaffold(topBar = {
        BackTopBar("Quiz · ${theme.title}", onBack) {
            if (quiz.isNotEmpty()) IconButton(onClick = { vm.generateQuiz(course, theme) }) {
                Icon(Icons.Default.Refresh, "Nouveau quiz")
            }
        }
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                quiz.isEmpty() -> {
                    if (busy == null) {
                        EmptyState("Pas encore de quiz", "Générez un quiz sur ce thème.")
                        Button(onClick = { vm.generateQuiz(course, theme) }, Modifier.fillMaxWidth()) { Text("Générer le quiz") }
                    }
                }
                index >= quiz.size -> {
                    val pct = score * 100 / quiz.size
                    Spacer(Modifier.height(32.dp))
                    Text(if (pct >= 80) "🏆" else if (pct >= 50) "👍" else "💪",
                        style = MaterialTheme.typography.displayLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("$score / ${quiz.size}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(
                        when {
                            pct >= 80 -> "Excellent, ce thème est maîtrisé !"
                            pct >= 50 -> "Bien joué ! Relis la fiche pour viser encore plus haut."
                            else -> "Relis la fiche et retente ta chance."
                        },
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { index = 0; score = 0; selected = null }, Modifier.fillMaxWidth()) { Text("Recommencer") }
                    OutlinedButton(onClick = { vm.generateQuiz(course, theme) }, Modifier.fillMaxWidth()) { Text("Nouvelles questions") }
                    TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Retour à la fiche") }
                }
                else -> {
                    val q = quiz[index]
                    LinearProgressIndicator(progress = { index / quiz.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("Question ${index + 1} / ${quiz.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(q.question, style = MaterialTheme.typography.titleLarge)
                    q.options.forEachIndexed { i, option ->
                        val answered = selected != null
                        val border = when {
                            answered && i == q.correctIndex -> Good
                            answered && i == selected -> Bad
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Card(
                            Modifier.fillMaxWidth().clickable(enabled = !answered) {
                                selected = i
                                if (i == q.correctIndex) score++
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(2.dp, border),
                            colors = CardDefaults.cardColors(
                                containerColor = if (answered && i == q.correctIndex) Good.copy(alpha = 0.12f)
                                else if (answered && i == selected) Bad.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${'A' + i}  ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(option, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (selected == null && q.hint.isNotBlank()) {
                        TextButton(onClick = { showHint = !showHint }) { Text(if (showHint) q.hint else "Besoin d'un indice ?") }
                    }
                    if (selected != null) {
                        val correct = selected == q.correctIndex
                        Text(if (correct) "Bonne réponse !" else "Pas tout à fait…",
                            color = if (correct) Good else Bad, fontWeight = FontWeight.Bold)
                        if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { index++; selected = null }, Modifier.fillMaxWidth()) {
                            Text(if (index + 1 < quiz.size) "Question suivante" else "Voir mon score")
                        }
                    }
                }
            }
        }
    }
}
