package tether.context

/** A 1-based, inclusive line range. */
data class Lines(val start: Int, val end: Int)

/** A structural element around the caret: its name, the IDE's kind word ("function", "method", "class", …) or null, and its lines. */
data class Symbol(val name: String, val kind: String?, val lines: Lines)

/**
 * Where the user is looking. [path] is absolute when captured and, once resolved for a chat ([ContextPaths.forNote]),
 * relative to that chat's folder, or still absolute for a file outside the project. [line] and [column] are 1-based
 * (the column counts characters). [container] is the nearest function-like element around [symbol], set only when
 * [symbol] itself is not function-like.
 */
data class EditorContext(
    val path: String,
    val line: Int,
    val column: Int,
    val selection: Lines? = null,
    val symbol: Symbol? = null,
    val container: Symbol? = null,
)

/** One structural element on the way from the outermost element around the caret to the innermost one. */
data class StructureHit(val symbol: Symbol, val functionLike: Boolean)

object Symbols {
    private val FUNCTION_WORDS = listOf("function", "method", "constructor", "lambda", "closure")
    private val FUNCTION_CLASS_PARTS = listOf("Function", "Method", "Lambda", "Constructor")

    /** The IDE's kind word as the note shows it: trimmed and lower case; null when the IDE has none. */
    fun kindWord(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * Function-like: the kind word names a function, or the PSI class does (a JS getter's kind word is "property",
     * its class `JSFunctionImpl`; Kotlin's is `KtNamedFunction`, Java's `PsiMethodImpl`).
     */
    fun isFunctionLike(kind: String?, psiClassName: String): Boolean =
        (kind != null && FUNCTION_WORDS.any { it in kind }) || FUNCTION_CLASS_PARTS.any { it in psiClassName }

    /** A name from a Structure View label: `calc(n: number): number` -> `calc`. */
    fun nameFromLabel(label: String?): String? = label?.substringBefore('(')?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [path] runs from the outermost element to the innermost. Returns the innermost element and, when that one is not
     * function-like, the nearest function-like element around it.
     */
    fun pick(path: List<StructureHit>): Pair<Symbol?, Symbol?> {
        val innermost = path.lastOrNull() ?: return null to null
        if (innermost.functionLike) return innermost.symbol to null
        return innermost.symbol to path.dropLast(1).lastOrNull { it.functionLike }?.symbol
    }
}

/** The text Claude gets for an [EditorContext] whose path is already relative to the chat's folder. */
object ContextNotes {
    /** The paragraph prepended to the user's message. Location only, never file contents. */
    fun note(c: EditorContext): String = buildString {
        append("[IDE] The user is looking at ${clean(c.path, PATH_MAX)}, caret at line ${c.line}:${c.column}")
        c.symbol?.let { append(", inside ").append(describe(it)) }
        c.container?.let { append(", within ").append(describe(it)) }
        append('.')
        c.selection?.let { append(" Selection: ").append(lines(it)).append('.') }
    }

    /**
     * Makes [s] safe to put in a note: every run of whitespace, control, format (bidi, zero-width) or
     * separator character (including NEL and the line and paragraph separators) becomes one space, the result is trimmed, backticks become apostrophes so they cannot close
     * a code span, and anything longer than [max] characters is cut to [max] - 1 characters plus "…".
     */
    internal fun clean(s: String, max: Int = 80): String {
        val flat = s.replace(CONTROL_RUN, " ").trim().replace('`', '\'')
        return if (flat.length > max) flat.take(max - 1) + "…" else flat
    }

    /** Paths keep their full length (Claude opens files by them); the cap only guards against pathological input. */
    private const val PATH_MAX = 400

    private val CONTROL_RUN = Regex("[\\s\\p{Cc}\\p{Cf}\\p{Z}]+")

    private fun describe(s: Symbol): String {
        val kind = s.kind?.let { clean(it) }?.takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
        return "$kind`${clean(s.name)}` (${lines(s.lines)})"
    }

    private fun lines(l: Lines) = if (l.start == l.end) "line ${l.start}" else "lines ${l.start}–${l.end}"
}
