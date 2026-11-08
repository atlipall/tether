package tether.report

import org.junit.Assert.assertEquals
import org.junit.Test

class TetherLogTest {
    private val log = """
        2026-11-08 10:00:00,001 [   100]   INFO - #c.i.o.a.Something - not ours
        2026-11-08 10:00:01,002 [   200]   WARN - #tether.session.Chats - Couldn't reopen session 1
        java.io.IOException: gone
        	at tether.session.Chats.reopen(Chats.kt:93)
        2026-11-08 10:00:02,003 [   300]   INFO - #c.i.o.a.Other - not ours either
        	at somewhere.Else(Else.kt:1)
        2026-11-08 10:00:03,004 [   400]   INFO - #tether.cli.ClaudeProcess - claude stderr: hello
    """.trimIndent().lineSequence()

    @Test fun keepsTetherEntriesWithTheirStackTraces() {
        assertEquals(
            listOf(
                "2026-11-08 10:00:01,002 [   200]   WARN - #tether.session.Chats - Couldn't reopen session 1",
                "java.io.IOException: gone",
                "\tat tether.session.Chats.reopen(Chats.kt:93)",
                "2026-11-08 10:00:03,004 [   400]   INFO - #tether.cli.ClaudeProcess - claude stderr: hello",
            ),
            TetherLog.recent(log),
        )
    }

    @Test fun keepsOnlyTheLatestEntries() {
        val lines = (1..10).map { "2026-11-08 10:00:0${it % 10},000 [ $it]   INFO - #tether.x - entry $it" }.asSequence()
        assertEquals(listOf("entry 9", "entry 10"), TetherLog.recent(lines, maxEntries = 2).map { it.substringAfterLast(" - ") })
    }

    @Test fun capsTheLineCount() {
        assertEquals(2, TetherLog.recent(log, maxLines = 2).size)
    }
}
