package fr.mescours.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.mescours.app.model.Course
import fr.mescours.app.ui.AppViewModel
import fr.mescours.app.ui.MathText
import fr.mescours.app.ui.theme.subjectColor
import java.text.DateFormat
import java.util.Date

/** Toutes les fiches d'une matière, regroupées par cours (du plus récent au plus ancien). */
@Composable
fun SubjectScreen(vm: AppViewModel, subject: String, onBack: () -> Unit, onOpenTheme: (String, String) -> Unit) {
    val library by vm.library.collectAsState()
    val selectedId by vm.selectedChildId.collectAsState()
    val courses = library.courses
        .filter { it.childId == selectedId && it.subject == subject }
        .sortedByDescending { it.createdAt }
    var toDelete by remember { mutableStateOf<Course?>(null) }
    val color = subjectColor(subject)

    Scaffold(topBar = { BackTopBar(subject, onBack) }) { padding ->
        if (courses.isEmpty()) {
            Column(Modifier.padding(padding)) { EmptyState("Plus de cours ici", "Ajoutez un cours depuis l'accueil.") }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            courses.forEach { course ->
                item(key = course.id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(course.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(course.createdAt)) +
                                    " · ${course.pagePaths.size} page(s) · " +
                                    if (course.notebookId != null) "NotebookLM" else "Gemini",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { toDelete = course }) { Icon(Icons.Default.Delete, "Supprimer le cours") }
                    }
                }
                items(course.themes, key = { course.id + it.id }) { theme ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenTheme(course.id, theme.id) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            MathText(theme.title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.padding(2.dp))
                            MathText(theme.summary, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.padding(top = 8.dp)) {
                                AssistChip(onClick = { onOpenTheme(course.id, theme.id) }, label = { Text("${theme.keyPoints.size} points clés") })
                                Spacer(Modifier.width(8.dp))
                                if (theme.quiz.isNotEmpty()) AssistChip(onClick = { onOpenTheme(course.id, theme.id) }, label = { Text("Quiz ✓") })
                                Spacer(Modifier.width(8.dp))
                                if (theme.exercises.isNotEmpty()) AssistChip(onClick = { onOpenTheme(course.id, theme.id) }, label = { Text("Exercices ✓") })
                            }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${c.title} » ?") },
            text = { Text("Les photos, fiches, quiz et exercices de ce cours seront effacés du téléphone." +
                if (c.notebookId != null) " Le carnet NotebookLM reste dans votre compte." else "") },
            confirmButton = { TextButton(onClick = { vm.deleteCourse(c.id); toDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}
