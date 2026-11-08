package tether.report

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware

/**
 * "Report a Tether Bug…": opens a new GitHub issue in the browser with the environment and Tether's recent IDE log
 * filled in, for the user to title and describe the problem, review and submit. Gathering runs on a pooled thread. It is in the
 * Help menu and the Tether tool window's options menu.
 */
class ReportBugAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            BugReports.openIssue(title = "", description = null)
        }
    }

    companion object {
        const val ID = "tether.ReportBug"
    }
}
