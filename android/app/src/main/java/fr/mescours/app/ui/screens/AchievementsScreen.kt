package fr.mescours.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.mescours.app.model.ChildProgress
import fr.mescours.app.model.Gamification
import fr.mescours.app.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

/** Tableau des succès de l'enfant sélectionné : niveau, statistiques et badges. */
@Composable
fun AchievementsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val library by vm.library.collectAsState()
    val selectedId by vm.selectedChildId.collectAsState()
    val child = library.children.find { it.id == selectedId } ?: library.children.firstOrNull()
    val progress = child?.let { library.progress[it.id] } ?: ChildProgress()
    val mastered = progress.bestScores.values.count { it >= 80 }

    Scaffold(topBar = { BackTopBar("Succès${child?.let { " de ${it.name}" } ?: ""}", onBack) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (child != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProgressHeader(child, progress, onClick = {})
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Mes chiffres") }
            item { StatTile("🧩", "${progress.quizzesCompleted}", "quiz terminés") }
            item { StatTile("💯", "${progress.perfectQuizzes}", "sans faute") }
            item { StatTile("✅", "${progress.correctAnswers}", "bonnes réponses") }
            item { StatTile("✍️", "${progress.exercisesDone.size}", "exercices réussis") }
            item { StatTile("⭐", "$mastered", "thèmes maîtrisés") }
            item { StatTile("🔥", "${progress.bestStreak}", "jours d'affilée (record)") }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("Badges · ${progress.badges.size}/${Gamification.BADGES.size}")
            }
            items(Gamification.BADGES, key = { it.id }) { badge ->
                val unlockedAt = progress.badges[badge.id]
                val unlocked = unlockedAt != null
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (unlocked) Color(0xFFFFF4D6) else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(10.dp).alpha(if (unlocked) 1f else 0.45f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (unlocked) badge.emoji else "🔒", fontSize = 34.sp)
                        Text(badge.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge, color = Color(0xFF3B2F00).takeIf { unlocked }
                                ?: MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            unlockedAt?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) }
                                ?: badge.description,
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(emoji: String, value: String, label: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 24.sp)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.height(32.dp))
    }
}
