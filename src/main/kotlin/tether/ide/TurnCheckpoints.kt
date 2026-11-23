package tether.ide

import tether.context.ContextNotes
import tether.follow.CheckoutMapper
import tether.session.Chat
import tether.session.ChatItem
import tether.session.Checkpoints
import tether.session.RevertChoice
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.history.LocalHistoryException
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl
import java.util.concurrent.ConcurrentHashMap

/** Most checkpoints kept per chat; older messages can't be reverted to. */
private const val MAX_PER_CHAT = 50

/**
 * Local History labels put as each message goes out to Claude, so a chat's checkout can be reverted to how it was
 * before any message of this run of the IDE (resumed history has none). Each revert is itself undoable from Local
 * History. Checkouts Local History does not track (excluded folders, such as worktrees by default) get no labels.
 */
@Service(Service.Level.PROJECT)
class TurnCheckpoints(private val project: Project) : Checkpoints {
    private class Checkpoint(val label: Label, val dir: String, val message: String)

    /** Chat id -> its newest checkpoints by message item id, oldest first. */
    private val checkpoints = ConcurrentHashMap<Int, MutableMap<String, Checkpoint>>()
    /** Chat id -> what to tell Claude with the next message after a revert. */
    private val notes = ConcurrentHashMap<Int, String>()

    override fun mark(chat: Chat, item: ChatItem.UserText, settled: Boolean) {
        try {
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByPath(chat.cwd)
            if (dir == null || !tracked(dir)) return
            refreshBeforeLabel(dir, settled)
            val message = shorten(item.text)
            val label = putLabelInOwnChangeSet(message)
            val forChat = checkpoints.computeIfAbsent(chat.id) {
                java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Checkpoint>() {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Checkpoint>) = size > MAX_PER_CHAT
                })
            }
            forChat[item.id] = Checkpoint(label, dir.path, message)
        } catch (e: Exception) {
            LOG.warn("Could not put a Local History label", e)
        }
    }

    /**
     * Puts the label for [message] from a write action on the EDT, so it gets a change set of its own. Local History
     * is shared by every open project: a label put while a command or refresh is open anywhere joins that change set,
     * and revert never finds a label whose change set changes nothing in its folder. In a write action none is half-open.
     */
    private fun putLabelInOwnChangeSet(message: String): Label =
        WriteAction.computeAndWait<Label, RuntimeException> {
            LocalHistory.getInstance().putSystemLabel(project, "Tether: before “$message”")
        }

    /**
     * Brings the VFS up to date with [dir] on disk, so writes the previous turn made behind the IDE's back land before
     * the next label, not after it. When the chat has [settled] and the native file watcher covers [dir], the watcher
     * has already marked those files dirty and refreshing just them is enough. Otherwise (a message sent right as a turn
     * ended, no watcher, or [dir] outside its roots) the whole folder is rescanned.
     */
    private fun refreshBeforeLabel(dir: VirtualFile, settled: Boolean) {
        val fs = LocalFileSystem.getInstance()
        if (settled && watched(dir)) fs.refresh(false) else VfsUtil.markDirtyAndRefresh(false, true, true, dir)
    }

    /**
     * Whether the native file watcher reports changes under [dir]: it runs, isn't still being given its roots, and has
     * no folder in or around [dir] that it could not watch. [dir] is project content (see [tracked]), and the platform
     * watches the content roots. Uses the platform's internal watcher API; there is no public one.
     */
    private fun watched(dir: VirtualFile): Boolean {
        val watcher = (LocalFileSystem.getInstance() as? LocalFileSystemImpl)?.fileWatcher ?: return false
        if (!watcher.isOperational || watcher.isSettingRoots) return false
        return watcher.manualWatchRoots.none { FileUtil.isAncestor(it, dir.path, false) || FileUtil.isAncestor(dir.path, it, false) }
    }

    /** Whether message [itemId] of chat [chatId] has a checkpoint its files can be reverted to. */
    fun hasCheckpoint(chatId: Int, itemId: String): Boolean = checkpoints[chatId]?.containsKey(itemId) == true

    /**
     * Asks what to revert and reverts the files when chosen. Refuses while the chat is busy, both before the dialog and
     * again after it: a turn's edits in flight would land after the revert, and a turn can open while the dialog is up
     * (a background agent finishing), so the user stops it (and sees what it did) first. Cancel is the dialog's default
     * button, so pressing Return by habit reverts nothing.
     */
    override fun revert(chat: Chat, item: ChatItem.UserText, conversation: Boolean): RevertChoice? {
        val title = "Revert to Before This Message"
        val checkpoint = checkpoints[chat.id]?.get(item.id)
        if (checkpoint == null && !conversation) {
            Messages.showInfoMessage(project, "This message is too old to revert to.", title)
            return null
        }
        if (chat.busy) return stillWorking(chat.agent.name)
        val dir = checkpoint?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it.dir) }
        val choices = RevertChoice.entries.filter { (!it.files || dir != null) && (!it.conversation || conversation) }
        val message = shorten(item.text)
        val text = buildString {
            append("Revert to before “$message”?\n\n")
            if (dir != null) {
                append("Files: every file in ${dir.presentableUrl} goes back to how it was then. This undoes ${chat.agent.name}'s ")
                append("changes from that message on, and your own changes since then too. You can undo it from Local History.\n\n")
            } else {
                append("The files can't be reverted: their folder isn't tracked by Local History, the message is too old, or its checkpoint isn't ready yet.\n\n")
            }
            if (conversation) append("Conversation: ${chat.agent.name} forgets this message and everything after it, and the message goes back into the input box.")
        }
        val buttons = choices.map { it.label } + Messages.getCancelButton()
        val answer = Messages.showDialog(project, text, title, buttons.toTypedArray(), buttons.lastIndex, Messages.getWarningIcon())
        val choice = choices.getOrNull(answer) ?: return null
        if (chat.busy) return stillWorking(chat.agent.name)
        if (choice.files && !revertFiles(chat, checkpoint!!, dir!!)) return null
        if (choice.files) notes[chat.id] = "The user reverted the files in this checkout to how they were before their message " +
            "“$message”. Changes made since then are gone; re-read files before relying on what you remember of them."
        return choice
    }

    /**
     * Records what to tell Claude after the conversation was rewound: nothing when the files were reverted too (its
     * memory and the files agree again), otherwise that the files may still hold later changes.
     */
    override fun conversationRewound(chat: Chat, item: ChatItem.UserText, filesReverted: Boolean) {
        if (filesReverted) {
            notes.remove(chat.id)
        } else {
            notes[chat.id] = "The user took the conversation back to before their message “${shorten(item.text)}”. " +
                "The files may still hold changes made after that point; re-read files before relying on what you remember of them."
        }
    }

    private fun stillWorking(agentName: String): RevertChoice? {
        Messages.showInfoMessage(project, "$agentName is still working. Stop it, or wait for it to finish, then revert.", "Revert to Before This Message")
        return null
    }

    /**
     * Reverts [dir] to [checkpoint]'s label; false (with a notice in the chat) when Local History can't. Saves and
     * refreshes first so Local History has the latest state, Claude's last writes included, and the revert is complete
     * and undoable.
     */
    private fun revertFiles(chat: Chat, checkpoint: Checkpoint, dir: VirtualFile): Boolean {
        FileDocumentManager.getInstance().saveAllDocuments()
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        try {
            targets(dir).forEach { checkpoint.label.revert(project, it) }
        } catch (e: LocalHistoryException) {
            LOG.warn("Revert failed", e)
            chat.session.notice("Couldn't revert: ${e.message}")
            return false
        }
        chat.session.notice("Reverted the files to how they were before “${checkpoint.message}”.", isError = false)
        return true
    }

    /** Whether Local History keeps [dir]'s files: inside the project's content and not excluded. */
    private fun tracked(dir: VirtualFile): Boolean =
        ReadAction.compute<Boolean, RuntimeException> { !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(dir) }

    /**
     * What to revert under [dir]: all of it, except that a project folder holding worktrees is reverted entry by entry
     * without `.claude`, so a main chat never reverts a worktree's files. (A top-level entry deleted since the label is
     * then not restored.)
     */
    private fun targets(dir: VirtualFile): List<VirtualFile> {
        if (dir.findFileByRelativePath(CheckoutMapper.WORKTREES) == null) return listOf(dir)
        return dir.children.filter { it.name != ".claude" }
    }

    /** The note for chat [chatId]'s next message after a revert, once. */
    fun consumeNote(chatId: Int): String? = notes.remove(chatId)

    fun forget(chatId: Int) {
        checkpoints.remove(chatId)
        notes.remove(chatId)
    }

    private fun shorten(text: String) = ContextNotes.clean(text, 60).ifEmpty { "(image)" }

    companion object {
        private val LOG = logger<TurnCheckpoints>()
        fun getInstance(project: Project): TurnCheckpoints = project.service()
    }
}
