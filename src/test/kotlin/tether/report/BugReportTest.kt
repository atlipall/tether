package tether.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class BugReportTest {
    private val environment = ReportEnvironment(
        tether = "0.2.7", ide = "WebStorm 2026.2 (WS-262.1)", os = "Mac OS X 27.0 (aarch64)",
        claude = "2.3.1 (Claude Code)", acpAgent = "hermes acp", settings = "IDE tools on",
    )

    private fun body(url: String): String =
        URLDecoder.decode(URI(url).rawQuery.substringAfter("body="), Charsets.UTF_8)

    private fun title(url: String): String =
        URLDecoder.decode(URI(url).rawQuery.substringAfter("title=").substringBefore("&body="), Charsets.UTF_8)

    @Test fun theReportHasTheDescriptionEnvironmentAndLog() {
        val text = BugReport("The send button does nothing.", environment, listOf("WARN - #tether.x - boom"), home = "/Users/me").markdown()
        assertTrue(text.startsWith("The send button does nothing.\n\n### Environment"))
        assertTrue(text.contains("- Tether: 0.2.7\n- IDE: WebStorm 2026.2 (WS-262.1)"))
        assertTrue(text.contains("### Recent Tether log\n\n```text\nWARN - #tether.x - boom\n```"))
    }

    @Test fun withoutADescriptionTheReaderIsAskedForOne() {
        val text = BugReport(null, environment, emptyList()).markdown()
        assertTrue(text.startsWith(BugReport.DESCRIBE))
        assertTrue(text.contains("Nothing from Tether in the IDE log."))
    }

    @Test fun anExceptionComesBeforeTheEnvironment() {
        val text = BugReport(null, environment, emptyList(), exception = "java.lang.IllegalStateException: no\n\tat a.B.c(B.kt:1)").markdown()
        assertTrue(text.indexOf("### Exception") in 0 until text.indexOf("### Environment"))
        assertTrue(text.contains("```text\njava.lang.IllegalStateException: no\n\tat a.B.c(B.kt:1)\n```"))
    }

    @Test fun pathsUnderHomeAreShownWithATilde() {
        val text = BugReport(null, environment, listOf("Couldn't read /Users/me/projects/x"), home = "/Users/me").markdown()
        assertTrue(text.contains("Couldn't read ~/projects/x"))
        assertFalse(text.contains("/Users/me"))
    }

    @Test fun theHomeFolderItselfAndBackslashPathsAreHiddenToo() {
        val text = BugReport(null, environment, listOf("cwd /Users/me", "at /Users/me: no"), home = "/Users/me").markdown()
        assertTrue(text.contains("cwd ~\n"))
        assertTrue(text.contains("at ~: no"))
        val windows = BugReport(null, environment, listOf("C:\\Users\\me\\x and C:/Users/me/y"), home = "C:\\Users\\me").markdown()
        assertTrue(windows.contains("~\\x and ~/y"))
    }

    @Test fun aLongerFolderNameStartingLikeHomeIsLeftAlone() {
        val text = BugReport(null, environment, listOf("/Users/meg/x"), home = "/Users/me").markdown()
        assertTrue(text.contains("/Users/meg/x"))
    }

    @Test fun theTitleHidesTheHomeFolderToo() {
        val url = BugReport(null, environment, emptyList(), home = "/Users/me").issueUrl("IOException: /Users/me/x.jsonl")
        assertEquals("IOException: ~/x.jsonl", title(url))
    }

    @Test fun aDescriptionTooLongForTheLinkIsCutAndSaysSo() {
        val description = (1..2000).joinToString(" ") { "word$it" }
        val url = BugReport(description, environment, listOf("a log line")).issueUrl("Title")
        assertTrue(url.length <= BugReport.MAX_URL)
        val body = body(url)
        assertTrue(body.startsWith("word1 word2 "))
        assertTrue(body.contains(BugReport.CUT_NOTE))
        assertFalse(body.contains("word2000"))
        assertTrue(body.contains("### Environment"))
    }

    @Test fun backticksInTheLogDontEndItsFence() {
        val text = BugReport(null, environment, listOf("a ``` b")).markdown()
        assertTrue(text.contains("````text\na ``` b\n````"))
    }

    @Test fun theIssueLinkCarriesTitleAndBody() {
        val report = BugReport("It broke", environment, listOf("one line"))
        val url = report.issueUrl("Send does nothing")
        assertTrue(url.startsWith("${BugReport.NEW_ISSUE}?title=Send%20does%20nothing&body="))
        assertEquals(report.markdown(), body(url))
    }

    @Test fun aLongLogLosesItsOldestLinesToFitTheLink() {
        val lines = (1..500).map { "line $it " + "x".repeat(60) }
        val url = BugReport("It broke", environment, lines).issueUrl("Title")
        assertTrue(url.length <= BugReport.MAX_URL)
        val body = body(url)
        assertTrue(body.contains("line 500 "))
        assertFalse(body.contains("line 1 "))
    }

    @Test fun aLongExceptionIsCutAtTheEndOnceTheLogIsGone() {
        val trace = "java.lang.Error: top\n" + (1..1000).joinToString("\n") { "\tat frame$it(F.kt:$it)" }
        val url = BugReport(null, environment, listOf("a log line"), exception = trace).issueUrl("Title")
        assertTrue(url.length <= BugReport.MAX_URL)
        val body = body(url)
        assertTrue(body.contains("java.lang.Error: top\n\tat frame1(F.kt:1)"))
        assertFalse(body.contains("frame1000("))
        assertFalse(body.contains("a log line"))
    }
}
