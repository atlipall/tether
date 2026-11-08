package tether.report

/** Picks Tether's entries out of the IDE log (`idea.log`). */
object TetherLog {
    /** An entry's first line: `2026-11-08 10:00:00,123 [  1234]   WARN - #tether.session.Chats - message`. */
    private val ENTRY_START = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private const val TETHER_CATEGORY = "- #tether."

    /**
     * The last [maxEntries] Tether entries in [lines], each with its continuation lines (a stack trace, say), at most
     * [maxLines] lines in all, oldest first.
     */
    fun recent(lines: Sequence<String>, maxEntries: Int = 60, maxLines: Int = 200): List<String> {
        val entries = ArrayDeque<List<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            if (ENTRY_START.containsMatchIn(line)) {
                current = if (TETHER_CATEGORY in line) mutableListOf(line).also { entries.addLast(it) } else null
                if (entries.size > maxEntries) entries.removeFirst()
            } else {
                current?.add(line)
            }
        }
        return entries.flatten().takeLast(maxLines)
    }
}
