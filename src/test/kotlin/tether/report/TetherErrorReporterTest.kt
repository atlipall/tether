package tether.report

import org.junit.Assert.assertEquals
import org.junit.Test

class TetherErrorReporterTest {
    @Test fun theTitleIsTheExceptionLine() {
        assertEquals(
            "java.lang.IllegalStateException: no",
            TetherErrorReporter.issueTitle("java.lang.IllegalStateException: no\n\tat a.B.c(B.kt:1)", "logged"),
        )
    }

    @Test fun withoutATraceTheTitleIsTheLoggedMessage() {
        assertEquals("Couldn't reopen session", TetherErrorReporter.issueTitle("", "Couldn't reopen session"))
        assertEquals("Couldn't reopen session", TetherErrorReporter.issueTitle(" \n ", "Couldn't reopen session"))
    }

    @Test fun theTitleIsKeptShort() {
        assertEquals(120, TetherErrorReporter.issueTitle("x".repeat(500), null).length)
    }
}
