package tether.ui

import tether.report.clipped
import com.intellij.openapi.diagnostic.logger
import org.cef.CefSettings.LogSeverity

/**
 * Passes the chat page's console errors and warnings (uncaught exceptions and rejected promises among them) to the IDE
 * log, so a broken chat panel leaves a trace for bug reports; other console messages are ignored. One page can log at
 * most [limit] messages, each [clipped] to [MAX_MESSAGE] characters; after that one line says the rest were left out,
 * so a page failing in a loop doesn't flood the log. Thread-safe.
 */
internal class PageConsole(private val limit: Int = DEFAULT_LIMIT, private val log: (String) -> Unit = { LOG.warn(it) }) {
    private var logged = 0

    /** Logs [message] from line [line] of [source] if [level] is an error or warning and this page is within its [limit]. */
    @Synchronized
    fun report(level: LogSeverity, message: String, source: String, line: Int) {
        if (level != LogSeverity.LOGSEVERITY_ERROR && level != LogSeverity.LOGSEVERITY_WARNING) return
        when {
            logged < limit -> log("Chat page: ${message.clipped(MAX_MESSAGE)} (${sourceName(source)}:$line)")
            logged == limit -> log("Chat page: more than $limit console errors and warnings; the rest are not logged")
            else -> return
        }
        logged++
    }

    /** Starts counting afresh, for a reloaded page. */
    @Synchronized
    fun reset() {
        logged = 0
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_MESSAGE = 1000
        val LOG = logger<PageConsole>()

        /** The file name at the end of [source], which for this page is otherwise a long internal URL. */
        fun sourceName(source: String): String = source.substringBefore('?').substringAfterLast('/').ifEmpty { "page" }
    }
}
