package fr.mescours.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

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

private val RoundShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun MescoursTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = RoundShapes,
        content = content,
    )
}

/** Dégradé vif d'une couleur vers une teinte voisine, pour les cartes et en-têtes. */
fun gradientOf(color: Color): Brush = Brush.linearGradient(
    listOf(color, lerp(color, Color(0xFFFF7AC6), 0.35f)),
)

/** Dégradé principal de l'app (violet → rose). */
val BrandGradient: Brush = Brush.linearGradient(listOf(Color(0xFF5B4CF0), Color(0xFF9B5CF6), Color(0xFFEC6FB0)))

/** Un emoji par matière pour des cartes plus parlantes. */
fun subjectEmoji(subject: String): String = when (subject.lowercase()) {
    "mathématiques", "maths" -> "🔢"
    "français" -> "📖"
    "histoire-géographie", "histoire", "géographie" -> "🌍"
    "svt" -> "🌱"
    "sciences" -> "🔬"
    "physique-chimie" -> "⚗️"
    "anglais" -> "🇬🇧"
    "espagnol" -> "🇪🇸"
    "allemand" -> "🇩🇪"
    "technologie" -> "⚙️"
    "emc" -> "⚖️"
    "philosophie" -> "💭"
    else -> "📚"
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
