package fr.mescours.app.util

/**
 * Convertit la notation LaTeX produite par l'IA ($\frac{2}{3}$, \times, x^{2}…) en texte
 * lisible par un enfant : symboles Unicode, exposants, et fractions séparées pour être
 * affichées empilées (voir ui/MathText.kt).
 */
object MathFormat {

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Fraction(val numerator: String, val denominator: String) : Segment
    }

    /** Découpe un texte en morceaux de texte et fractions. */
    fun parse(input: String): List<Segment> {
        val p = Parser(stripDelimiters(input))
        val out = mutableListOf<Segment>()
        val buf = StringBuilder()
        p.parseInto(out, buf, topLevel = true)
        if (buf.isNotEmpty()) out += Segment.Text(buf.toString())
        return merge(out)
    }

    /** Version texte seul (fractions écrites « a⁄b »), pour les endroits sans rendu empilé. */
    fun toPlainText(input: String): String = parse(input).joinToString("") {
        when (it) {
            is Segment.Text -> it.text
            is Segment.Fraction -> inlineFraction(it.numerator, it.denominator)
        }
    }

    fun containsMath(input: String) = input.contains('\\') || input.contains('$') || input.contains('^')

    // ------------------------------------------------------------------ interne

    private fun merge(list: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in list) {
            val last = out.lastOrNull()
            if (s is Segment.Text && last is Segment.Text) out[out.lastIndex] = Segment.Text(last.text + s.text)
            else if (!(s is Segment.Text && s.text.isEmpty())) out += s
        }
        // Les espaces LaTeX (\, \quad…) laissent souvent des doubles espaces.
        return out.mapIndexed { idx, seg ->
            if (seg !is Segment.Text) seg
            else Segment.Text(seg.text.replace(MULTI_SPACE, " ").let { if (idx == 0) it.trimStart() else it })
        }
    }

    /** Retire les délimiteurs $…$, $$…$$, \(…\), \[…\] (les « \$ » échappés restent des $). */
    private fun stripDelimiters(s: String): String = s
        .replace("\\$", "\u0000")
        .replace("$$", "")
        .replace("$", "")
        .replace("\\(", "").replace("\\)", "")
        .replace("\\[", "").replace("\\]", "")
        .replace("\u0000", "$")

    internal fun inlineFraction(n: String, d: String): String {
        val sn = n.map { SUPERSCRIPT[it] }
        val sd = d.map { SUBSCRIPT[it] }
        return if (sn.all { it != null } && sd.all { it != null } && n.isNotEmpty() && d.isNotEmpty())
            sn.joinToString("") + "⁄" + sd.joinToString("")
        else "${wrap(n)}/${wrap(d)}"
    }

    private val MULTI_SPACE = Regex(" {2,}")

    private fun wrap(s: String) = if (s.length > 1 && s.any { it in "+-−×÷ " }) "($s)" else s

    private val SYMBOLS = mapOf(
        "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "leqslant" to "≤", "ge" to "≥", "geq" to "≥", "geqslant" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "simeq" to "≃", "equiv" to "≡", "infty" to "∞",
        "pi" to "π", "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "Delta" to "Δ",
        "theta" to "θ", "lambda" to "λ", "mu" to "µ", "sigma" to "σ", "omega" to "ω", "Omega" to "Ω",
        "degree" to "°", "circ" to "°", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "Rightarrow" to "⇒", "Leftrightarrow" to "⇔", "iff" to "⇔", "implies" to "⇒",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "angle" to "∠", "parallel" to "∥", "perp" to "⊥",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩", "emptyset" to "∅",
        "mathbb{N}" to "ℕ", "mathbb{Z}" to "ℤ", "mathbb{R}" to "ℝ", "mathbb{Q}" to "ℚ",
        "%" to "%", "{" to "{", "}" to "}", "&" to "&", "#" to "#", "_" to "_",
        "," to " ", ";" to " ", ":" to " ", "!" to "", "quad" to " ", "qquad" to "  ", " " to " ",
        "left" to "", "right" to "", "displaystyle" to "", "textstyle" to "", "limits" to "",
        "euro" to "€", "times10" to "×10",
    )

    /** Commandes dont on garde simplement l'argument. */
    private val PASS_THROUGH = setOf(
        "text", "textbf", "textit", "mathrm", "mathbf", "mathit", "operatorname", "mbox", "boxed",
        "overline", "underline", "vec", "overrightarrow", "bar", "hat", "mathsf", "textrm", "emph",
    )

    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'k' to 'ᵏ', 'm' to 'ᵐ', 'p' to 'ᵖ', 't' to 'ᵗ', 'y' to 'ʸ', '°' to '°',
    )
    private val SUBSCRIPT = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'n' to 'ₙ', 'x' to 'ₓ', 'k' to 'ₖ', 'm' to 'ₘ', 'p' to 'ₚ', 't' to 'ₜ',
    )

    private class Parser(val s: String) {
        var i = 0

        /**
         * Lit jusqu'à la fin (ou jusqu'à « } » si !topLevel). Les fractions de premier niveau
         * deviennent des segments ; les fractions imbriquées sont écrites en ligne.
         */
        fun parseInto(out: MutableList<Segment>, buf: StringBuilder, topLevel: Boolean) {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '}' && !topLevel -> { i++; return }
                    c == '{' -> { i++; buf.append(group()) }
                    c == '}' -> i++
                    c == '\\' -> command(out, buf, topLevel)
                    c == '^' -> { i++; buf.append(script(SUPERSCRIPT, "^")) }
                    c == '_' && looksLikeMathSubscript() -> { i++; buf.append(script(SUBSCRIPT, "_")) }
                    c == '-' && i + 1 < s.length && s[i + 1].isDigit() && i > 0 && s[i - 1] == ' ' -> { buf.append('−'); i++ }
                    else -> { buf.append(c); i++ }
                }
            }
        }

        /** Contenu d'un groupe déjà ouvert « { », converti en texte. */
        fun group(): String {
            val sub = mutableListOf<Segment>()
            val b = StringBuilder()
            parseInto(sub, b, topLevel = false)
            if (b.isNotEmpty()) sub += Segment.Text(b.toString())
            return sub.joinToString("") {
                when (it) {
                    is Segment.Text -> it.text
                    is Segment.Fraction -> inlineFraction(it.numerator, it.denominator)
                }
            }
        }

        /** Argument : {…} ou un seul caractère / une commande. */
        fun argument(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            return when (s[i]) {
                '{' -> { i++; group() }
                '\\' -> {
                    val tmp = StringBuilder()
                    command(mutableListOf(), tmp, topLevel = false)
                    tmp.toString()
                }
                else -> s[i++].toString()
            }
        }

        fun script(map: Map<Char, Char>, marker: String): String {
            val arg = argument()
            val mapped = arg.map { map[it] }
            return if (arg.isNotEmpty() && mapped.all { it != null }) mapped.joinToString("")
            else if (marker == "^") "^($arg)" else "($arg)"
        }

        /** « _ » isolé dans un mot (ex. nom_de_fichier) : on le garde tel quel. */
        fun looksLikeMathSubscript(): Boolean {
            val next = s.getOrNull(i + 1) ?: return false
            return next == '{' || next.isDigit() || (next.isLetter() && s.getOrNull(i + 2)?.isLetter() != true)
        }

        fun command(out: MutableList<Segment>, buf: StringBuilder, topLevel: Boolean) {
            i++ // backslash
            if (i >= s.length) return
            val start = i
            if (s[i].isLetter()) {
                while (i < s.length && s[i].isLetter()) i++
            } else {
                i++ // commande d'un caractère : \, \% \{ …
            }
            val name = s.substring(start, i)
            when {
                name in setOf("frac", "dfrac", "tfrac", "cfrac") -> {
                    val n = argument().trim()
                    val d = argument().trim()
                    if (topLevel) {
                        if (buf.isNotEmpty()) { out += Segment.Text(buf.toString()); buf.clear() }
                        out += Segment.Fraction(n, d)
                    } else buf.append(inlineFraction(n, d))
                }
                name == "sqrt" -> {
                    var index = ""
                    if (i < s.length && s[i] == '[') {
                        val end = s.indexOf(']', i)
                        if (end > 0) { index = s.substring(i + 1, end); i = end + 1 }
                    }
                    val arg = argument()
                    val idx = index.map { SUPERSCRIPT[it] ?: it }.joinToString("")
                    buf.append(idx).append('√').append(if (arg.any { !it.isLetterOrDigit() && it != ',' && it != '.' }) "($arg)" else arg)
                }
                name == "widehat" -> buf.append("∠").append(argument())
                name == "mathbb" -> {
                    val arg = argument()
                    buf.append(SYMBOLS["mathbb{$arg}"] ?: arg)
                }
                name in PASS_THROUGH -> buf.append(argument())
                SYMBOLS.containsKey(name) -> buf.append(SYMBOLS.getValue(name))
                else -> Unit // commande inconnue : ignorée, son argument éventuel est lu normalement
            }
        }
    }
}
