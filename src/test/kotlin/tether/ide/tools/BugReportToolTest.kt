package tether.ide.tools

import tether.report.BugReport
import tether.report.BugReports
import tether.report.ReportEnvironment
import tether.settings.TetherSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class BugReportToolTest : BasePlatformTestCase() {
    private val environment = ReportEnvironment("0.2.7", "WebStorm", "macOS", "2.3.1", "none", "IDE tools on")
    private val descriptions = mutableListOf<String?>()

    private fun tool(): BugReportTool {
        val dir = project.basePath!!
        return BugReportTool(ChatIdeHost(project, dir) { dir }) { description ->
            descriptions += description
            BugReport(description, environment, listOf("a log line"))
        }
    }

    fun testWithoutArgumentsItReturnsTheReportAndNoLink() {
        val out = tool().call(buildJsonObject {})
        assertFalse(out.isError)
        assertTrue(out.text.contains("- Tether: 0.2.7"))
        assertTrue(out.text.contains("a log line"))
        assertFalse(out.text.contains(BugReport.NEW_ISSUE))
        assertEquals(listOf<String?>(null), descriptions)
    }

    fun testWithTitleAndDescriptionItReturnsTheIssueLink() {
        val out = tool().call(buildJsonObject {
            put("title", JsonPrimitive("Send does nothing"))
            put("description", JsonPrimitive("Clicking send does nothing."))
        })
        assertTrue(out.text.contains("${BugReport.NEW_ISSUE}?title=Send%20does%20nothing&body=Clicking%20send%20does%20nothing."))
        assertFalse("the report already went back on the first call", out.text.contains("- Tether: 0.2.7"))
        assertEquals(listOf<String?>("Clicking send does nothing."), descriptions)
    }

    fun testTheToolIsLentEvenWithIdeToolsOff() {
        val state = TetherSettings.getInstance().state
        val before = state.ideTools
        state.ideTools = false
        try {
            val dir = project.basePath!!
            assertEquals(listOf("bug_report"), ChatIdeHost(project, dir) { dir }.tools.map { it.name })
        } finally {
            state.ideTools = before
        }
    }

    fun testTheRealReportIsCollectedFromTheIde() {
        val text = BugReports.collect("Something odd").markdown()
        assertTrue(text, text.startsWith("Something odd\n\n### Environment"))
        assertTrue(text, text.contains("- IDE: WebStorm"))
        assertTrue(text, Regex("- Claude Code: \\S").containsMatchIn(text))
    }
}
