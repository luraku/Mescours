package fr.mescours.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
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
import fr.mescours.app.ui.MathText
import fr.mescours.app.util.MathFormat

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
    var attempt by rememberSaveable(quizKey) { mutableIntStateOf(0) }
    // Les IA placent souvent la bonne réponse en premier : on mélange les propositions
    // à chaque partie (l'ordre affiché change, la bonne réponse reste repérée par son index d'origine).
    val orders = remember(quizKey, attempt) { quiz.map { it.options.indices.shuffled() } }

    Scaffold(topBar = {
        BackTopBar("Quiz · ${MathFormat.toPlainText(theme.title)}", onBack) {
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
                    Button(onClick = { index = 0; score = 0; selected = null; attempt++ }, Modifier.fillMaxWidth()) { Text("Recommencer") }
                    OutlinedButton(onClick = { vm.generateQuiz(course, theme) }, Modifier.fillMaxWidth()) { Text("Nouvelles questions") }
                    TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Retour à la fiche") }
                }
                else -> {
                    val q = quiz[index]
                    LinearProgressIndicator(
                        progress = { (index + if (selected != null) 1 else 0) / quiz.size.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
                        drawStopIndicator = {},
                    )
                    Text("Question ${index + 1} / ${quiz.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MathText(q.question, style = MaterialTheme.typography.titleLarge)
                    orders.getOrElse(index) { q.options.indices.toList() }.forEachIndexed { position, i ->
                        val option = q.options.getOrElse(i) { "" }
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
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(2.dp, border),
                            colors = CardDefaults.cardColors(
                                containerColor = if (answered && i == q.correctIndex) Good.copy(alpha = 0.12f)
                                else if (answered && i == selected) Bad.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(34.dp).background(
                                        when {
                                            answered && i == q.correctIndex -> Good
                                            answered && i == selected -> Bad
                                            else -> MaterialTheme.colorScheme.primaryContainer
                                        },
                                        CircleShape,
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (answered && i == q.correctIndex) "✓" else if (answered && i == selected) "✗" else "${'A' + position}",
                                        fontWeight = FontWeight.Bold,
                                        color = if (answered && (i == q.correctIndex || i == selected)) Color.White
                                        else MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                MathText(option, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (selected == null && q.hint.isNotBlank()) {
                        TextButton(onClick = { showHint = !showHint }) { MathText(if (showHint) q.hint else "Besoin d'un indice ?") }
                    }
                    if (selected != null) {
                        val correct = selected == q.correctIndex
                        Text(if (correct) "Bonne réponse !" else "Pas tout à fait…",
                            color = if (correct) Good else Bad, fontWeight = FontWeight.Bold)
                        if (q.explanation.isNotBlank()) MathText(q.explanation, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            if (index + 1 == quiz.size) vm.recordQuiz(course, theme, score, quiz.size)
                            index++
                            selected = null
                        }, Modifier.fillMaxWidth()) {
                            Text(if (index + 1 < quiz.size) "Question suivante" else "Voir mon score")
                        }
                    }
                }
            }
        }
    }
}
