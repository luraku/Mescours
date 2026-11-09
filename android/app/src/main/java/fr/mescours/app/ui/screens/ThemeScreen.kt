package fr.mescours.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.mescours.app.ui.AppViewModel
import fr.mescours.app.ui.MathText
import fr.mescours.app.util.MathFormat
import fr.mescours.app.ui.theme.subjectColor

/** Fiche de révision d'un thème + accès au quiz et aux exercices. */
@Composable
fun ThemeScreen(
    vm: AppViewModel,
    courseId: String,
    themeId: String,
    onBack: () -> Unit,
    onQuiz: () -> Unit,
    onExercises: () -> Unit,
) {
    val library by vm.library.collectAsState()
    val course = library.courses.find { it.id == courseId }
    val theme = course?.themes?.find { it.id == themeId }
    if (course == null || theme == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val color = subjectColor(course.subject)

    Scaffold(topBar = { BackTopBar(MathFormat.toPlainText(theme.title), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = color),
                ) {
                    Column(Modifier.padding(20.dp).fillMaxWidth()) {
                        Text(course.subject.uppercase() + " · " + course.title, color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium)
                        MathText(theme.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        MathText(theme.summary, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (theme.quiz.isEmpty()) vm.generateQuiz(course, theme)
                        onQuiz()
                    }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Default.Quiz, null); Text("  Quiz")
                    }
                    FilledTonalButton(onClick = {
                        if (theme.exercises.isEmpty()) vm.generateExercises(course, theme)
                        onExercises()
                    }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Default.EditNote, null); Text("  Exercices")
                    }
                }
            }
            if (theme.keyPoints.isNotEmpty()) {
                item { SectionTitle("À retenir") }
                items(theme.keyPoints) { p ->
                    Row {
                        Text("•  ", color = color, fontWeight = FontWeight.Bold)
                        MathText(p, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (theme.vocabulary.isNotEmpty()) {
                item { SectionTitle("Vocabulaire") }
                items(theme.vocabulary) { t ->
                    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
                        Column(Modifier.padding(12.dp).fillMaxWidth()) {
                            MathText(t.term, fontWeight = FontWeight.Bold, color = color)
                            MathText(t.definition, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (theme.examples.isNotEmpty()) {
                item { SectionTitle("Exemples") }
                items(theme.examples) { e -> MathText("→ $e", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
