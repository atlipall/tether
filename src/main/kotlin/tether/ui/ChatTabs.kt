package tether.ui

import tether.follow.CheckoutFollower
import tether.session.Chat
import tether.session.ChatSpec
import tether.session.Chats
import tether.session.Transcript
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import java.util.concurrent.atomic.AtomicBoolean

/** One closeable tool-window tab per chat. Closing a tab closes its chat (stops its process). EDT only. */
class ChatTabs(private val project: Project, private val toolWindow: ToolWindow) {
    private val chats get() = Chats.getInstance(project)

    /**
     * Opens [spec]'s chat in a new, selected tab. The [Chats] service is captured up front, since it may be unreachable
     * while the project is closing, when the tab is disposed. The tab's name follows the chat's title, set on the EDT
     * since a branch move renames the chat from the CLI reader thread.
     */
    fun open(spec: ChatSpec, resumed: Transcript? = chats.read(spec)): Chat {
        val registry = chats
        val chat = registry.open(spec, resumed)
        val content = ContentFactory.getInstance().createContent(null, chat.title, false)
        content.isCloseable = true
        content.putUserData(CHAT, chat)
        val closed = AtomicBoolean(false)
        Disposer.register(content) { closed.set(true); chat.onStatusChanged = null; registry.close(chat) }
        val panel = ChatPanel(project, chat, parent = content)
        content.component = panel.browser.component
        content.preferredFocusableComponent = panel.browser.component
        showStatusIcon(content, chat, closed)
        chat.onTitleChanged = {
            ApplicationManager.getApplication().invokeLater({ if (!closed.get()) content.displayName = chat.title }, project.disposed)
        }
        val follower = CheckoutFollower.getInstance(project)
        chat.onCwdChanged = {
            ApplicationManager.getApplication().invokeLater({ follower.refresh() }, project.disposed)
        }
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)
        return chat
    }

    /**
     * Makes [content]'s icon and the tool window's badge follow [chat]'s state (see [TabAttention]). The tool window
     * draws icons only for contents that ask for them.
     */
    private fun showStatusIcon(content: Content, chat: Chat, closed: AtomicBoolean) {
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
        chat.onStatusChanged = { turnEnded ->
            ApplicationManager.getApplication().invokeLater({
                if (!closed.get()) TabAttention.chatChanged(toolWindow, content, turnEnded)
            }, project.disposed)
        }
    }

    /** Selects [chat]'s tab; false when it has none. */
    fun select(chat: Chat): Boolean {
        val content = toolWindow.contentManager.contents.firstOrNull { it.getUserData(CHAT) === chat } ?: return false
        toolWindow.contentManager.setSelectedContent(content, true)
        return true
    }

    fun selectedChat(): Chat? = toolWindow.contentManager.selectedContent?.getUserData(CHAT)

    /** Opens a new chat in the project. */
    fun newChat() {
        open(chats.mainSpec())
    }

    /** Shows the popup of checkouts and past sessions; [place] positions it. */
    fun chooseWorkspace(place: (JBPopup) -> Unit) = NewChatPopup.show(project, this, place)

    companion object {
        val CHAT: Key<Chat> = Key.create("tether.chat")

        fun find(project: Project): ChatTabs? =
            ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.ID)?.let { ChatTabs(project, it) }
    }
}
