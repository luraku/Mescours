package fr.mescours.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF5B4CF0)
private val Jaune = Color(0xFFFFC94D)

private val Light = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E2FF),
    onPrimaryContainer = Color(0xFF1B0F7A),
    secondary = Color(0xFF00A88F),
    tertiary = Jaune,
    background = Color(0xFFF8F7FD),
    surface = Color(0xFFF8F7FD),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A1A9E),
    primaryContainer = Color(0xFF3F30C4),
    secondary = Color(0xFF4FDBC0),
    tertiary = Jaune,
)

@Composable
fun MescoursTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Une couleur par matière, façon cartes de révision. */
fun subjectColor(subject: String): Color = when (subject.lowercase()) {
    "mathématiques", "maths" -> Color(0xFF3D7BFF)
    "français" -> Color(0xFFE5484D)
    "histoire-géographie", "histoire", "géographie" -> Color(0xFFB7791F)
    "svt", "sciences" -> Color(0xFF2F9E44)
    "physique-chimie" -> Color(0xFF7048E8)
    "anglais", "espagnol", "allemand" -> Color(0xFFE8590C)
    "technologie" -> Color(0xFF0C8599)
    "emc", "philosophie" -> Color(0xFFC2255C)
    else -> {
        val palette = listOf(0xFF5B4CF0, 0xFF0CA678, 0xFFF08C00, 0xFF1C7ED6, 0xFFD6336C)
        Color(palette[Math.floorMod(subject.hashCode(), palette.size)])
    }
}
