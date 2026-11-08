package tether.ide.tools

import tether.cli.ToolOutput
import tether.report.BugReport
import tether.report.BugReports
import kotlinx.serialization.json.JsonObject

/**
 * Lets the agent help the user report a problem with Tether itself, whether or not anything failed: it hands back the
 * report ([BugReports.collect]) and, given a title and description, a link that opens the GitHub issue filled in. The
 * user reviews and submits the issue on GitHub; the tool sends nothing anywhere.
 */
internal class BugReportTool(host: ChatIdeHost, private val collect: (String?) -> BugReport = BugReports::collect) : IdeTool(host, listOf(
    Arg("title", ArgType.STRING, "The issue's title: the problem in a few words.", required = false),
    Arg(
        "description", ArgType.STRING,
        "Markdown: what happened, what the user expected and the steps to reproduce it. The environment and log are added for you.",
        required = false,
    ),
)) {
    override val name = "bug_report"
    override val readOnly = true
    override val description =
        "Prepares a bug report for Tether, the IDE plugin that hosts this chat (not for bugs in the user's project). " +
            "Use it when the user wants to report a problem with Tether or asks for a bug report, even if nothing failed. " +
            "Without arguments it returns the Tether, IDE, OS and Claude Code versions, Tether's settings and its recent " +
            "IDE log lines, to help work out what went wrong. Then write the report with the user and call it again with " +
            "title and description: it returns a link that opens a new GitHub issue with everything filled in. Give the " +
            "user that link to review and submit; don't file the issue another way unless they ask. Paths under the home " +
            "folder are shown as ~; point out anything else in the log the user may not want to make public."

    override fun run(args: JsonObject): ToolOutput {
        val title = args.optionalString("title")
        val description = args.optionalString("description")
        val report = collect(description)
        if (title == null || description == null) return ToolOutput(report.markdown())
        return ToolOutput(
            "Open this link to review and submit the issue on GitHub; it has the description, the environment and " +
                "Tether's recent log:\n${report.issueUrl(title)}",
        )
    }
}
