package tether.report

import org.junit.Assert.assertEquals
import org.junit.Test

class BugReportsTest {
    @Test fun anAgentCommandShowsOnlyItsProgram() {
        assertEquals("hermes (with arguments)", BugReports.agentProgram(listOf("/opt/bin/hermes", "acp", "--api-key", "secret")))
        assertEquals("hermes", BugReports.agentProgram(listOf("hermes")))
        assertEquals("my agent", BugReports.agentProgram(listOf("/Applications/My Tools/my agent")))
        assertEquals("agent (with arguments)", BugReports.agentProgram(listOf("API_KEY=secret", "agent", "--x")))
        assertEquals("set", BugReports.agentProgram(listOf("API_KEY=secret")))
        assertEquals("none", BugReports.agentProgram(emptyList()))
    }
}
