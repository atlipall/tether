package tether.ide

import tether.git.BranchSwitcher
import tether.git.SwitchOutcome
import tether.git.canonicalPath
import tether.git.checkoutRoot
import tether.git.currentBranch
import tether.session.BranchBar
import tether.session.Chat
import tether.session.Chats
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFrame

/**
 * Warns a chat whose checkout is on another branch than the one it worked on ([Chat.homeBranch]), as when a tab is
 * selected after another chat's work switched the shared checkout, and offers to switch back. A busy chat takes on
 * whatever branch its checkout moves to ([Chat.observeBranch]), so its own branch moves never warn it. Each chat also
 * shows the branch its checkout is on now.
 *
 * Every open chat's branch and [BranchBar] are worked out again when a tab is selected or closed, a chat's status
 * changes or it sends a message, the IDE comes back to the front, or a checkout's HEAD file changes. The tool window
 * and the chat page call [refresh] for their events; the guard listens for the last two itself. EDT only.
 */
@Service(Service.Level.PROJECT)
class BranchGuard(private val project: Project) : Disposable {
    private val chats = Chats.getInstance(project)

    /** The branch a folder's checkout is on (tests inject it). */
    var branchOf: (String) -> String? = ::currentBranch

    var switcher = BranchSwitcher()

    /** Runs the switch off the EDT, and its result back on it (tests run both inline). */
    var background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) }
    var onEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it, project.disposed) }

    /** Reloads a checkout's files after a switch, so editors and the Project view show the other branch's files. */
    var reload: (String) -> Unit = { root ->
        LocalFileSystem.getInstance().refreshAndFindFileByPath(root)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
    }

    /**
     * What the user did with a chat's bar for one pair of branches: [dismissed] it, or tried to switch and got [error].
     * Forgotten once either branch changes.
     */
    private data class Note(val home: String, val current: String, val dismissed: Boolean = false, val error: String? = null)
    private val notes = HashMap<Int, Note>()

    /** Checkouts being switched now: their chats show the switch in progress and offer no other. */
    private val switching = HashSet<String>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) = refresh()
        })
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any(::isHeadChange)) onEdt { refresh() }
            }
        })
    }

    /**
     * Tells each open chat its checkout's branch ([Chat.observeBranch]) and shows it the bar it needs now; forgets the
     * notes of closed chats and of branches that moved on.
     */
    fun refresh() {
        val open = chats.all()
        notes.keys.retainAll(open.map { it.id }.toSet())
        val checkouts = Checkouts(open)
        for (chat in open) {
            val current = branchOf(chat.cwd)
            chat.observeBranch(current)
            chat.showBranchBar(barFor(chat, current, checkouts))
        }
    }

    /** Hides [chat]'s bar until its branch or the checkout's changes. */
    fun dismiss(chat: Chat) {
        val bar = chat.branchBar ?: return
        notes[chat.id] = Note(bar.home, bar.current, dismissed = true)
        refresh()
    }

    /**
     * Switches [chat]'s checkout back to its branch when its bar offers that. Saves the open documents first, so edits
     * still in editors count as uncommitted changes. Refused, as the bar then says, when anything is uncommitted.
     */
    fun switch(chat: Chat) {
        if (chat.busy) return
        val checkouts = Checkouts(chats.all())
        val bar = barFor(chat, branchOf(chat.cwd), checkouts) ?: return
        if (bar.blocked != null || bar.switching) return
        val root = checkouts.rootOf(chat) ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        notes.remove(chat.id)
        switching += root
        refresh()
        background {
            val outcome = switcher.switch(root, bar.home)
            onEdt { switched(chat, bar, root, outcome) }
        }
    }

    private fun switched(chat: Chat, bar: BranchBar, root: String, outcome: SwitchOutcome) {
        switching -= root
        val error = when (outcome) {
            SwitchOutcome.Switched -> null
            is SwitchOutcome.Uncommitted -> BranchBar.uncommitted(outcome.changes)
            is SwitchOutcome.Failed -> outcome.message
        }
        if (error == null) reload(root) else notes[chat.id] = Note(bar.home, bar.current, error = error)
        refresh()
    }

    /** The open chats' checkout roots, as canonical paths, each found once per refresh. */
    private class Checkouts(val open: List<Chat>) {
        private val roots = open.associateWith { chat -> checkoutRoot(chat.cwd)?.let(::canonicalPath) }

        fun rootOf(chat: Chat): String? = roots[chat]

        /** Whether a chat other than [chat] is working in [chat]'s checkout. */
        fun busyBesides(chat: Chat): Boolean {
            val root = rootOf(chat) ?: return false
            return open.any { it !== chat && it.busy && roots[it] == root }
        }
    }

    /** [chat]'s bar while its checkout is on [current], with what the user did with it for these branches. */
    private fun barFor(chat: Chat, current: String?, checkouts: Checkouts): BranchBar? {
        val bar = BranchBar.of(chat.homeBranch, current, checkouts.busyBesides(chat))
        val note = notes[chat.id]?.takeIf { bar != null && it.home == bar.home && it.current == bar.current }
        if (note == null) notes.remove(chat.id)
        if (bar == null || note?.dismissed == true) return null
        return bar.copy(switching = checkouts.rootOf(chat) in switching, error = note?.error)
    }

    /** A checkout's HEAD file: `.git/HEAD`, or a linked worktree's `.git/worktrees/<name>/HEAD`. */
    private fun isHeadChange(event: VFileEvent): Boolean = HEAD_FILE.containsMatchIn(event.path)

    override fun dispose() {}

    companion object {
        private val HEAD_FILE = Regex("/\\.git(/worktrees/[^/]+)?/HEAD$")

        fun getInstance(project: Project): BranchGuard = project.service()
    }
}
