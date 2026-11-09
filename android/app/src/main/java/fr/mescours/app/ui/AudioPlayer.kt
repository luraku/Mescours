package fr.mescours.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/** Petit lecteur pour le résumé audio (lecture/pause, position, durée). */
@Composable
fun AudioPlayer(path: String, color: Color, modifier: Modifier = Modifier) {
    val player = remember(path) {
        runCatching { MediaPlayer().apply { setDataSource(path); prepare() } }.getOrNull()
    }
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableFloatStateOf(0f) }
    var duration by remember(path) { mutableIntStateOf(player?.duration ?: 0) }

    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false; position = 0f; it.seekTo(0) }
        onDispose { player?.release() }
    }
    LaunchedEffect(player, playing) {
        while (playing && player != null) {
            position = player.currentPosition.toFloat()
            duration = player.duration
            delay(500)
        }
    }

    if (player == null) {
        Text("Lecture impossible de ce fichier audio.", color = MaterialTheme.colorScheme.error, modifier = modifier)
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(onClick = {
            if (playing) player.pause() else player.start()
            playing = !playing
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Lecture")
        }
        Slider(
            value = position,
            onValueChange = { position = it; player.seekTo(it.toInt()) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text("${fmt(position.toInt())} / ${fmt(duration)}", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun fmt(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
