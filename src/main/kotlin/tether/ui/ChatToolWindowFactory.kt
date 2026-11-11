package tether.ui

import tether.follow.CheckoutFollower
import tether.session.ChatSpec
import tether.session.Agent
import tether.session.Chats
import tether.session.Transcript
import tether.session.OpenChats
import tether.report.ReportBugAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.jcef.JBCefApp

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setAdditionalGearActions(DefaultActionGroup(ActionManager.getInstance().getAction(ReportBugAction.ID)))
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("Tether needs JCEF, which is not available in this IDE runtime.")
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, null, false))
            return
        }
        toolWindow.setTitleActions(listOf(NewChatAction(), WorkspaceChatAction(), OpenSettingsAction()))
        val tabs = ChatTabs(project, toolWindow)
        followSelectedTab(project, toolWindow)
        TabAttention.watch(project, toolWindow)
        var restoring = true
        restore(project, tabs, toolWindow) { restoring = false }
        openMainChatWhenShownEmpty(project, toolWindow, tabs) { restoring }
    }

    /**
     * Keeps the [CheckoutFollower] on the selected tab's chat. Registered before the first tab, so selecting that one
     * is followed too. A closed tab's chat leaves [Chats] only when its content is disposed, so the refresh after a
     * removal waits a turn of the EDT.
     */
    private fun followSelectedTab(project: Project, toolWindow: ToolWindow) {
        val follower = CheckoutFollower.getInstance(project)
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) follower.follow(event.content.getUserData(ChatTabs.CHAT))
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                ApplicationManager.getApplication().invokeLater({ follower.refresh() }, project.disposed)
            }
        })
    }

    /** Starts a fresh main chat when the tool window is shown after every tab was closed, unless [restoring] says the tabs are still being reopened. */
    private fun openMainChatWhenShownEmpty(project: Project, toolWindow: ToolWindow, tabs: ChatTabs, restoring: () -> Boolean) {
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == ID && !restoring() && shown.contentManager.contentCount == 0) tabs.open(Chats.getInstance(project).mainSpec())
            }
        })
    }

    /**
     * Reopens the chats that were open when the project closed (a fresh main chat when there are none). Their
     * transcripts are read in the background, so a long session doesn't hold up the IDE; then [done] runs and the tabs
     * open. A session the user opened meanwhile is not opened twice. Only then does [OpenChats] start tracking: a save
     * meanwhile would store an empty list.
     */
    private fun restore(project: Project, tabs: ChatTabs, toolWindow: ToolWindow, done: () -> Unit) {
        val chats = Chats.getInstance(project)
        background(project, { readRestorable(chats) }, expired = { project.isDisposed || toolWindow.isDisposed }, failed = "Couldn't reopen the chats") { restored ->
            done()
            for ((spec, resumed) in restored.filter { it.first.resumeId !in chats.openSessionIds(it.first.agentId ?: Agent.CLAUDE.id) }) {
                try {
                    tabs.open(spec, resumed)
                } catch (e: Exception) {
                    if (e is ControlFlowException) throw e
                    LOG.warn("Couldn't reopen session ${spec.resumeId}", e)
                }
            }
            if (toolWindow.contentManager.contentCount == 0) tabs.open(chats.mainSpec())
            OpenChats.getInstance(project).startTracking()
        }
    }

    /** [Chats.restorable], or none (logged) if even the list can't be read. Off the EDT. */
    private fun readRestorable(chats: Chats): List<Pair<ChatSpec, Transcript>> = try {
        chats.restorable()
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e
        LOG.warn("Couldn't read the sessions to reopen", e)
        emptyList()
    }

    companion object {
        const val ID = "Tether"
        private val LOG = logger<ChatToolWindowFactory>()
    }
}
