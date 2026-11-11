package tether.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.IconUtil
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import javax.swing.Icon

/**
 * The signals that draw the user to a chat tab. Each tab's icon shows a question mark while its chat waits for the user
 * (a question to answer or a tool to allow), a spinner while it works, and a blue dot once a turn ended out of view,
 * until the tab is shown. The tool window's button gets the platform's blue badge while any tab waits or has a dot, so a
 * hidden tool window still asks for attention. EDT only.
 */
internal object TabAttention {
    /** Set on a tab whose turn ended while it was out of view; cleared when the tab is shown. */
    private val UNREAD: Key<Boolean> = Key.create("tether.unread")

    /** The platform's question-dialog icon, scaled down to the size of a tab icon. */
    private val WAITING_ICON: Icon by lazy { IconUtil.resizeSquared(AllIcons.General.QuestionDialog, 16) }

    /** A tab-sized icon with a dot in the platform's information-badge blue at its centre. */
    private val UNREAD_ICON: Icon by lazy { ColorIcon(16, 16, 7, 7, JBUI.CurrentTheme.IconBadge.INFORMATION, false, 7) }

    /** The tool window's icon as `plugin.xml` declares it, with or without the blue badge. */
    private val button by lazy { BadgeIconSupplier(IconLoader.getIcon("/icons/tether.svg", TabAttention::class.java)) }

    /** Clears the dot of the tab the user sees whenever they select a tab or show the tool window. */
    fun watch(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) markSeen(toolWindow)
            }

            override fun contentRemoved(event: ContentManagerEvent) = refreshButton(toolWindow)
        })
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown === toolWindow) markSeen(toolWindow)
            }
        })
    }

    /** The chat in [content] changed state; [turnEnded] says it went from busy to idle. */
    fun chatChanged(toolWindow: ToolWindow, content: Content, turnEnded: Boolean) {
        if (turnEnded && !inView(toolWindow, content)) content.putUserData(UNREAD, true)
        redraw(toolWindow, content)
    }

    private fun markSeen(toolWindow: ToolWindow) {
        if (!toolWindow.isVisible) return
        val content = toolWindow.contentManager.selectedContent ?: return
        if (content.getUserData(UNREAD) != true) return
        content.putUserData(UNREAD, null)
        redraw(toolWindow, content)
    }

    private fun redraw(toolWindow: ToolWindow, content: Content) {
        content.icon = tabIcon(content)
        refreshButton(toolWindow)
    }

    private fun refreshButton(toolWindow: ToolWindow) {
        val wanted = toolWindow.contentManager.contents.any { it.getUserData(ChatTabs.CHAT)?.waiting == true || it.getUserData(UNREAD) == true }
        toolWindow.setIcon(button.getInfoIcon(wanted))
    }

    private fun inView(toolWindow: ToolWindow, content: Content): Boolean =
        toolWindow.isVisible && toolWindow.contentManager.selectedContent === content

    private fun tabIcon(content: Content): Icon? {
        val chat = content.getUserData(ChatTabs.CHAT) ?: return null
        return when {
            chat.waiting -> WAITING_ICON
            chat.busy -> AnimatedIcon.Default.INSTANCE
            content.getUserData(UNREAD) == true -> UNREAD_ICON
            else -> null
        }
    }
}
