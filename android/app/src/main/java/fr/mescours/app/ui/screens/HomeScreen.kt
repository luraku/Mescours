package fr.mescours.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.mescours.app.model.Child
import fr.mescours.app.model.GRADES
import fr.mescours.app.ui.AppViewModel
import fr.mescours.app.ui.theme.subjectColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenSubject: (String) -> Unit,
    onCapture: () -> Unit,
    onSettings: () -> Unit,
) {
    val library by vm.library.collectAsState()
    val selectedId by vm.selectedChildId.collectAsState()
    val child = library.children.find { it.id == selectedId } ?: library.children.firstOrNull()
    var showAddChild by remember { mutableStateOf(library.children.isEmpty()) }
    var confirmDelete by remember { mutableStateOf<Child?>(null) }

    val courses = library.courses.filter { it.childId == child?.id }
    val subjects = courses.groupBy { it.subject }.toList().sortedBy { it.first }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes Cours", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Réglages") }
                },
            )
        },
        floatingActionButton = {
            if (child != null) {
                ExtendedFloatingActionButton(
                    onClick = onCapture,
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    text = { Text("Nouveau cours") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(library.children, key = { it.id }) { c ->
                    FilterChip(
                        selected = c.id == child?.id,
                        onClick = { vm.selectChild(c.id) },
                        label = { Text("${c.name} · ${c.grade}") },
                        leadingIcon = {
                            Box(
                                Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(c.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { showAddChild = true },
                        label = { Text("Ajouter") },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                    )
                }
            }

            when {
                child == null -> EmptyState(
                    "Bienvenue !",
                    "Ajoutez un premier enfant pour commencer à ranger ses cours.",
                )
                subjects.isEmpty() -> EmptyState(
                    "Aucun cours pour ${child.name}",
                    "Prenez en photo une leçon du cahier : l'IA la découpe en fiches par thème, " +
                        "puis crée des quiz et des exercices.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Matières de ${child.name}", style = MaterialTheme.typography.titleMedium)
                    }
                    items(subjects, key = { it.first }) { (subject, list) ->
                        val themes = list.sumOf { it.themes.size }
                        val quizzes = list.sumOf { c -> c.themes.count { it.quiz.isNotEmpty() } }
                        SubjectCard(subject, "${list.size} cours · $themes fiches", "$quizzes quiz prêts") {
                            onOpenSubject(subject)
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = { confirmDelete = child }) {
                            Text("Supprimer le profil de ${child.name}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAddChild) {
        AddChildDialog(
            onDismiss = { showAddChild = false },
            onAdd = { name, grade -> vm.addChild(name, grade); showAddChild = false },
        )
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer ${c.name} ?") },
            text = { Text("Tous ses cours, fiches et quiz seront effacés de ce téléphone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteChild(c.id); confirmDelete = null }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SubjectCard(subject: String, line1: String, line2: String, onClick: () -> Unit) {
    val color = subjectColor(subject)
    Card(
        Modifier.fillMaxWidth().height(130.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(subject, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column {
                Text(line1, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                Text(line2, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📚", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChildDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf("6e") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter un enfant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Prénom") }, singleLine = true)
                ExposedDropdownMenuBox(expanded, { expanded = it }) {
                    OutlinedTextField(
                        value = grade, onValueChange = {}, readOnly = true,
                        label = { Text("Classe") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        GRADES.forEach { g ->
                            DropdownMenuItem(text = { Text(g) }, onClick = { grade = g; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, grade) }, enabled = name.isNotBlank()) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
