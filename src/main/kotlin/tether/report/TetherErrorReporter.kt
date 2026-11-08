package tether.report

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.diagnostic.SubmittedReportInfo.SubmissionStatus
import com.intellij.util.Consumer
import java.awt.Component

/**
 * The "Report to Tether on GitHub" button of the IDE's error dialog, for exceptions thrown by Tether: opens a new issue
 * in the browser with the exception, what the user wrote in the dialog, the environment and Tether's recent IDE log.
 * The user reviews and submits it on GitHub; nothing is sent from the IDE itself. The dialog is told the report
 * failed when the issue couldn't be opened.
 */
class TetherErrorReporter : ErrorReportSubmitter() {
    override fun getReportActionText() = "Report to Tether on GitHub"

    override fun getPrivacyNoticeText() =
        "Opens a new GitHub issue in your browser with the error, versions and Tether's recent IDE log lines. " +
            "Nothing is sent until you submit it there."

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val first = events.firstOrNull() ?: return false
        val exception = events.joinToString("\n\n") { it.throwableText.ifBlank { it.message.orEmpty() } }
        val title = issueTitle(first.throwableText, first.message)
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val status = if (BugReports.openIssue(title, additionalInfo, exception)) SubmissionStatus.NEW_ISSUE else SubmissionStatus.FAILED
            app.invokeLater { consumer.consume(SubmittedReportInfo(status)) }
        }
        return true
    }

    internal companion object {
        private const val MAX_TITLE = 120

        /**
         * The issue title for an error: the first line of its [throwableText] (the exception and its message), or the
         * logged [message] when there is no trace, at most [MAX_TITLE] characters.
         */
        fun issueTitle(throwableText: String, message: String?): String =
            (throwableText.lineSequence().firstOrNull { it.isNotBlank() } ?: message.orEmpty()).trim().take(MAX_TITLE)
    }
}
