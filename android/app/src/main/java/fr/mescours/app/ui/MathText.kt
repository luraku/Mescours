package fr.mescours.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import fr.mescours.app.util.MathFormat

/**
 * Texte avec mathématiques lisibles : la notation LaTeX de l'IA est convertie
 * (×, ÷, ², √, π…) et les fractions sont affichées empilées, comme dans un cahier.
 */
@Composable
fun MathText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val segments = remember(text) { MathFormat.parse(text) }
    val fractions = segments.filterIsInstance<MathFormat.Segment.Fraction>()
    if (fractions.isEmpty() || maxLines < Int.MAX_VALUE) {
        // Pas de fraction (ou texte tronqué) : simple texte converti, fractions écrites ²⁄₃.
        Text(
            remember(text) { MathFormat.toPlainText(text) }, modifier, color = color, style = style,
            fontWeight = fontWeight, maxLines = maxLines, overflow = overflow,
        )
        return
    }

    val inkColor = if (color != Color.Unspecified) color
    else if (style.color != Color.Unspecified) style.color else LocalContentColor.current
    val baseSize = if (style.fontSize.isSpecified) style.fontSize else 16.sp
    val inline = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        segments.forEachIndexed { index, seg ->
            when (seg) {
                is MathFormat.Segment.Text -> append(seg.text)
                is MathFormat.Segment.Fraction -> {
                    val id = "frac$index"
                    appendInlineContent(id, "${seg.numerator}/${seg.denominator}")
                    val chars = maxOf(seg.numerator.length, seg.denominator.length, 1)
                    inline[id] = InlineTextContent(
                        Placeholder(
                            width = (chars * 0.55f + 0.5f).em,
                            height = 2.2.em,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        )
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            FractionPart(seg.numerator, baseSize.value * 0.82f, inkColor, fontWeight)
                            Box(
                                Modifier.fillMaxWidth(0.92f).height(1.5.dp).background(inkColor)
                            )
                            FractionPart(seg.denominator, baseSize.value * 0.82f, inkColor, fontWeight)
                        }
                    }
                }
            }
        }
    }
    Text(
        annotated, modifier, color = color, style = style, fontWeight = fontWeight,
        inlineContent = inline,
    )
}

@Composable
private fun FractionPart(text: String, size: Float, color: Color, fontWeight: FontWeight?) {
    Text(
        text,
        color = color,
        fontSize = size.sp,
        lineHeight = (size * 1.05f).sp,
        fontWeight = fontWeight,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}
