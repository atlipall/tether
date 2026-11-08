package tether.ui

import org.cef.CefSettings.LogSeverity
import org.junit.Assert.assertEquals
import org.junit.Test

class PageConsoleTest {
    private val logged = mutableListOf<String>()
    private val console = PageConsole(limit = 2) { logged += it }

    @Test fun logsTheMessageWithTheFileNameAndLine() {
        console.report(LogSeverity.LOGSEVERITY_ERROR, "Uncaught TypeError: x is undefined", "file:///jbcefbrowser/123/chat.js?v=1", 42)
        assertEquals(listOf("Chat page: Uncaught TypeError: x is undefined (chat.js:42)"), logged)
    }

    @Test fun stopsAfterTheLimitWithOneNote() {
        repeat(5) { console.report(LogSeverity.LOGSEVERITY_WARNING, "error $it", "chat.js", it) }
        assertEquals(
            listOf("Chat page: error 0 (chat.js:0)", "Chat page: error 1 (chat.js:1)", "Chat page: more than 2 console errors and warnings; the rest are not logged"),
            logged,
        )
    }

    @Test fun ignoresInfoAndDebugMessages() {
        console.report(LogSeverity.LOGSEVERITY_INFO, "hello", "chat.js", 1)
        console.report(LogSeverity.LOGSEVERITY_VERBOSE, "detail", "chat.js", 2)
        assertEquals(emptyList<String>(), logged)
    }

    @Test fun aReloadedPageLogsAgain() {
        repeat(3) { console.report(LogSeverity.LOGSEVERITY_ERROR, "error", "chat.js", 1) }
        console.reset()
        console.report(LogSeverity.LOGSEVERITY_ERROR, "after reload", "chat.js", 1)
        assertEquals("Chat page: after reload (chat.js:1)", logged.last())
    }
}
