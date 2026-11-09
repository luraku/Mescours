package fr.mescours.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import fr.mescours.app.ui.AppViewModel
import fr.mescours.app.ui.AudioPlayer
import fr.mescours.app.ui.MathText
import fr.mescours.app.ui.theme.subjectColor
import fr.mescours.app.ui.theme.subjectEmoji
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Détail d'un cours : photos d'origine, résumés audio générés et fiches. */
@Composable
fun CourseScreen(vm: AppViewModel, courseId: String, onBack: () -> Unit, onOpenTheme: (String) -> Unit) {
    val library by vm.library.collectAsState()
    val course = library.courses.find { it.id == courseId }
    if (course == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val color = subjectColor(course.subject)
    var viewerPage by remember { mutableStateOf<Int?>(null) }
    val audioThemes = course.themes.filter { t -> t.audioPath?.let { File(it).exists() } == true || t.audioTaskId != null }

    Scaffold(topBar = { BackTopBar(course.title, onBack) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "${subjectEmoji(course.subject)} ${course.subject} · " +
                        DateFormat.getDateInstance(DateFormat.LONG).format(Date(course.createdAt)) +
                        " · " + if (course.notebookId != null) "NotebookLM" else "Gemini",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Photos
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("📷 Photos du cours (${course.pagePaths.size})") }
            if (course.pagePaths.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Hint("Aucune photo enregistrée pour ce cours.") }
            }
            itemsIndexed(course.pagePaths, key = { i, p -> "$i$p" }) { index, path ->
                val exists = remember(path) { File(path).exists() }
                Box(
                    Modifier
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = exists) { viewerPage = index },
                    contentAlignment = Alignment.Center,
                ) {
                    if (exists) {
                        AsyncImage(File(path), "Page ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("Photo\nsupprimée", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "${index + 1}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            // ---- Résumés audio
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("🎧 Résumés audio") }
            if (audioThemes.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Hint(
                        if (course.notebookId != null) "Aucun résumé audio pour l'instant : ouvre une fiche ci-dessous pour en générer un."
                        else "Les résumés audio sont disponibles pour les cours créés avec NotebookLM."
                    )
                }
            }
            items(audioThemes, key = { "audio-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { theme ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
                ) {
                    Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        MathText(theme.title, fontWeight = FontWeight.Bold, color = color,
                            modifier = Modifier.clickable { onOpenTheme(theme.id) })
                        val path = theme.audioPath
                        if (path != null && File(path).exists()) {
                            AudioPlayer(path, color)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                CircularProgressIndicator(Modifier.padding(end = 10.dp).size(20.dp), strokeWidth = 2.dp, color = color)
                                Text("En cours de génération…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ---- Fiches
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("📝 Fiches (${course.themes.size})") }
            items(course.themes, key = { "theme-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { theme ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenTheme(theme.id) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        MathText(theme.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (theme.audioPath != null) Text("🎧 ")
                        if (theme.quiz.isNotEmpty()) Text("🧩 ")
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }

    viewerPage?.let { start ->
        PhotoViewer(course.pagePaths, start, onDismiss = { viewerPage = null })
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Visionneuse plein écran : balayage entre les pages, pincer pour zoomer, double-tap pour réinitialiser. */
@Composable
private fun PhotoViewer(paths: List<String>, start: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { paths.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                AsyncImage(
                    File(paths[page]),
                    "Page ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                        }
                        .pointerInput(page) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            Text(
                "${pager.currentPage + 1} / ${paths.size}",
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, "Fermer", tint = Color.White)
            }
        }
    }
}
