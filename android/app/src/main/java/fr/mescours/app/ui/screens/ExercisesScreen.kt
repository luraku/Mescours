package fr.mescours.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.mescours.app.model.Exercise
import fr.mescours.app.ui.AppViewModel

@Composable
fun ExercisesScreen(vm: AppViewModel, courseId: String, themeId: String, onBack: () -> Unit) {
    val library by vm.library.collectAsState()
    val busy by vm.busy.collectAsState()
    val course = library.courses.find { it.id == courseId }
    val theme = course?.themes?.find { it.id == themeId }
    if (course == null || theme == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(topBar = {
        BackTopBar("Exercices · ${theme.title}", onBack) {
            if (theme.exercises.isNotEmpty()) IconButton(onClick = { vm.generateExercises(course, theme) }) {
                Icon(Icons.Default.Refresh, "Nouveaux exercices")
            }
        }
    }) { padding ->
        if (theme.exercises.isEmpty()) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                if (busy == null) {
                    EmptyState("Pas encore d'exercices", "Générez des exercices corrigés sur ce thème.")
                    Button(onClick = { vm.generateExercises(course, theme) }, Modifier.fillMaxWidth()) { Text("Générer les exercices") }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(theme.exercises, key = { i, e -> "$i-${e.statement.hashCode()}" }) { i, ex -> ExerciseCard(i + 1, ex) }
        }
    }
}

@Composable
private fun ExerciseCard(number: Int, ex: Exercise) {
    var showHint by rememberSaveable(ex.statement) { mutableStateOf(false) }
    var showSolution by rememberSaveable(ex.statement) { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Exercice $number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(ex.difficulty) })
            }
            Text(ex.statement, style = MaterialTheme.typography.bodyLarge)
            Row {
                if (ex.hint.isNotBlank()) TextButton(onClick = { showHint = !showHint }) { Text(if (showHint) "Masquer l'indice" else "Indice") }
                TextButton(onClick = { showSolution = !showSolution }) { Text(if (showSolution) "Masquer la correction" else "Voir la correction") }
            }
            if (showHint) Text("💡 ${ex.hint}", style = MaterialTheme.typography.bodyMedium)
            if (showSolution) {
                HorizontalDivider()
                Text("Correction", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text(ex.solution, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
