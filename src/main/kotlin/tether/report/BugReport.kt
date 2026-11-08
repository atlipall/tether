package tether.report

import java.net.URLEncoder

/** What a bug report says about where Tether runs. Each value is as shown to the reader, placeholders included. */
data class ReportEnvironment(
    val tether: String,
    val ide: String,
    val os: String,
    val claude: String,
    val acpAgent: String,
    val settings: String,
) {
    /** The environment as a markdown list. */
    fun markdown(): String = listOf(
        "Tether" to tether,
        "IDE" to ide,
        "OS" to os,
        "Claude Code" to claude,
        "ACP agent" to acpAgent,
        "Settings" to settings,
    ).joinToString("\n") { (label, value) -> "- $label: $value" }
}

/**
 * A bug report for Tether's GitHub issues: what the reporter wrote, an exception if there was one, the
 * [environment] and recent Tether lines from the IDE log ([TetherLog]). Paths under [home] are shown as `~`, in the
 * issue's title as well as its body, whichever separator they are written with.
 */
class BugReport(
    description: String?,
    private val environment: ReportEnvironment,
    private val logLines: List<String>,
    private val exception: String? = null,
    private val home: String = System.getProperty("user.home"),
) {
    private val description = description?.trim()?.takeIf { it.isNotEmpty() }

    /** The whole report as markdown. */
    fun markdown(): String = markdown(description, logLines, exception)

    /**
     * A link that opens a new issue titled [title] with this report filled in. When the report makes the link longer
     * than browsers and GitHub accept, the oldest log lines are left out first, then the end of the exception, and
     * last the end of the description, which then says it was cut.
     */
    fun issueUrl(title: String): String {
        val shownTitle = hideHome(title)
        var text = description
        var lines = logLines
        var trace = exception?.lines()
        while (true) {
            val url = url(shownTitle, markdown(text, lines, trace?.joinToString("\n")))
            if (url.length <= MAX_URL) return url
            when {
                lines.isNotEmpty() -> lines = lines.drop(maxOf(1, lines.size / 4))
                trace != null && trace.size > 1 -> trace = trace.take(trace.size / 2)
                text != null && text.length > MIN_DESCRIPTION -> text = cut(text)
                else -> return url
            }
        }
    }

    private fun markdown(description: String?, lines: List<String>, trace: String?): String = buildString {
        append(description ?: DESCRIBE)
        append("\n\n")
        trace?.let { append("### Exception\n\n").append(fence(it)).append("\n\n") }
        append("### Environment\n\n").append(environment.markdown()).append("\n\n")
        append("### Recent Tether log\n\n")
        append(if (lines.isEmpty()) "Nothing from Tether in the IDE log." else fence(lines.joinToString("\n")))
        append('\n')
    }.let(::hideHome)

    /**
     * [text] with [home] shown as `~`, written with either separator, wherever it is a whole path segment: at the
     * end of the text or followed by anything that can't continue a folder name, so `/Users/me` in `/Users/meg` stays.
     */
    private fun hideHome(text: String): String {
        val trimmed = home.trimEnd('/', '\\')
        if (trimmed.length < 2) return text
        return setOf(trimmed, trimmed.replace('\\', '/'), trimmed.replace('/', '\\'))
            .fold(text) { shown, form -> shown.replace(Regex(Regex.escape(form) + """(?![\w.-])"""), "~") }
    }

    private fun url(title: String, body: String): String =
        "$NEW_ISSUE?title=${encode(title)}&body=${encode(body)}"

    companion object {
        const val REPOSITORY = "https://github.com/atlipall/tether"
        const val NEW_ISSUE = "$REPOSITORY/issues/new"

        /** Longest issue link we open; GitHub turns away links much past 8 KB, so this leaves some room. */
        internal const val MAX_URL = 7500

        /** A description is never cut shorter than this; past it, the link is left too long. */
        private const val MIN_DESCRIPTION = 200

        internal const val CUT_NOTE = "*(The rest was cut to fit the issue link.)*"

        internal const val DESCRIBE = "Describe what happened, what you expected, and the steps that lead to it."

        /** [text] without its last quarter, ending with [CUT_NOTE]. */
        private fun cut(text: String): String {
            val kept = text.removeSuffix(CUT_NOTE).trimEnd()
            return kept.take(kept.length * 3 / 4).trimEnd() + "\n\n" + CUT_NOTE
        }

        private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        /** [text] in a code fence long enough that backticks inside it don't end it. */
        private fun fence(text: String): String {
            val longestRun = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
            val ticks = "`".repeat(maxOf(3, longestRun + 1))
            return "${ticks}text\n$text\n$ticks"
        }
    }
}
