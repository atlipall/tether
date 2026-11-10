package tether.session

import tether.cli.CliConnection
import tether.cli.ContextUsage
import tether.cli.ModelChoice
import tether.cli.PermissionMode
import tether.cli.SessionLaunch
import tether.cli.SlashCommand
import tether.context.ContextNotes
import tether.context.EditorContext
import tether.git.WorkingTrees
import tether.git.WorktreeBranches
import tether.git.currentBranch
import tether.image.Attachment
import tether.image.ImageFiles
import tether.image.PendingAttachments
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Receives one chat's updates. Methods may be called from any thread. */
interface SessionView {
    fun reset(items: List<ChatItem>)
    fun item(item: ChatItem)
    fun state(state: SessionState, mode: String?)

    /** The modes the agent offers; empty hides the selector. */
    fun modes(modes: List<Choice>) {}

    /** The agents the chat offers and the selected one; [locked] once it has started. */
    fun agent(agents: List<Agent>, selected: Agent, locked: Boolean) {}
    fun insert(text: String)

    /** What the IDE window shows for this chat (`IDE: <checkout>`); empty hides it. */
    fun checkout(text: String) {}

    /** What Claude is doing now (changes only); null when no turn is running. */
    fun activity(activity: Activity?) {}

    /** How full the context window is (the latest report). */
    fun context(usage: ContextUsage) {}

    /** The model and effort in use and the choices (the latest report). */
    fun model(choice: ModelChoice) {}

    /** A predicted next prompt for the empty message box, valid until the next state other than IDLE. */
    fun suggestion(text: String) {}

    /** The slash commands to offer while typing `/` at the start of the message box. */
    fun commands(commands: List<SlashCommand>) {}

    /** The messages waiting for the running turn to end, oldest first; empty hides them. */
    fun queue(messages: List<QueuedMessage>) {}

    /** The background processes running now, oldest first; empty shows none. */
    fun processes(processes: List<BackgroundProcess>) {}
}

/** What "Revert to here" takes back to before a message, as the user chose it. */
enum class RevertChoice(val label: String, val files: Boolean, val conversation: Boolean) {
    FILES_AND_CONVERSATION("Files and Conversation", true, true),
    FILES("Files Only", true, false),
    CONVERSATION("Conversation Only", false, true),
}

/** Marks the files' state before each message of a chat, and reverts to such a mark. */
interface Checkpoints {
    /**
     * Marks the files before [item] goes out, when their folder can be reverted. [settled]: the chat was idle for at
     * least [SETTLE_MS] before it, so the IDE's file watcher has reported every write of the previous turn. Pooled
     * thread, outside every lock.
     */
    fun mark(chat: Chat, item: ChatItem.UserText, settled: Boolean)

    /**
     * Asks what to revert to before [item] ([conversation]: whether the conversation can be), and reverts the files
     * if that is chosen. Null when nothing more is to happen (cancelled, refused, or the file revert failed). EDT.
     */
    fun revert(chat: Chat, item: ChatItem.UserText, conversation: Boolean): RevertChoice?

    /** The conversation was taken back to before [item] after [revert] chose it; [filesReverted]: the files were too. EDT. */
    fun conversationRewound(chat: Chat, item: ChatItem.UserText, filesReverted: Boolean)

    companion object {
        /** How long a chat must have been idle for its previous turn's writes to have reached the IDE's file watcher. */
        const val SETTLE_MS = 1000L
    }
}

/** Tells a chat what happened in git that it did not do itself. */
interface GitNotes {
    /**
     * Calls [done] on the EDT, at once or later, exactly once, with the note for [chat]'s next message (what changed in
     * git since its last turn ended) or null. EDT.
     */
    fun note(chat: Chat, done: (String?) -> Unit)

    /** [chat]'s turn ended: what git looks like now is what it knows. On the CLI reader thread with the session lock held: must be quick. */
    fun turnEnded(chat: Chat)
}

/**
 * One chat tab's model: its own [AgentSession] and agent process, the items shown so far, and the page showing them.
 * A new chat is a draft until [start]: its agent can change, and no process runs.
 */
class Chat(
    val id: Int,
    private val spec: ChatSpec,
    history: List<ChatItem>,
    /** The uuid of the resumed transcript's newest assistant message; null for a new chat. */
    private val resumeAnchor: String? = null,
    private val connector: (SessionLaunch) -> CliConnection,
    private val editNote: (Chat) -> String?,
    /**
     * Where the user is looking, as this chat names the file; null for none (or when the setting is off). EDT, cheap:
     * it must not search for symbols.
     */
    private val editorContext: (Chat) -> EditorContext? = { null },
    /**
     * The same with the symbol, which may need a background lookup: calls `done` on the EDT, at once or later, exactly
     * once. Null means [editorContext] is already complete.
     */
    private val resolveContext: ((Chat, (EditorContext?) -> Unit) -> Unit)? = null,
    /**
     * Notes on what happened in git since the last turn; null for none. A message that goes out while a turn runs gets
     * none: Claude's own git commands in that turn would be reported back to it as someone else's. The turn's end
     * (going idle, or dying, after one ran) is reported instead.
     */
    private val gitNotes: GitNotes? = null,
    private val log: (String) -> Unit = {},
    /** When the session began (epoch millis): now for a new chat, the transcript's start for a resumed one. */
    val startedAt: Long? = null,
    /** Tools and edit checks the IDE lends this chat's Claude; null for none. */
    private val ide: IdeHost? = null,
    /** Runs IDE work off the CLI reader thread and outside the session lock. */
    private val background: (Runnable) -> Unit = { it.run() },
    /** Marks the files' state as each message goes out, so they can be reverted to it; null for none. */
    private val checkpoints: Checkpoints? = null,
    /** The agents the draft can run, Claude first. */
    val agents: List<Agent> = listOf(Agent.CLAUDE),
    agent: Agent = agents.first(),
    /** Makes an ACP agent's session from its command line. */
    private val acpSession: (List<String>, ChatListener) -> AgentSession = { _, _ -> error("No ACP agent is configured.") },
    /** Called on the EDT when the user selects an agent for the draft. */
    private val onAgentSelected: (Agent) -> Unit = {},
    /** The commands each agent last reported in any chat, offered by a chat whose own process hasn't reported yet. */
    private val commandCatalog: CommandCatalog = CommandCatalog(),
    /** The name shown until the user gives one (`main`, `main 2`, a worktree name); follows later branch moves, see [followBranch]. */
    defaultName: String = spec.label,
    /** Stores a name the user gave in the session's transcript (blank: none), marked archived or not. Called off the EDT. */
    private val saveTitle: ((cwd: String, sessionId: String, title: String, archived: Boolean) -> Unit)? = null,
    /** The default name for a chat moved to a branch shown as the given name, numbered past the other chats' names. */
    private val nameForBranch: (String) -> String = { it },
) {
    val label: String = spec.label

    /** The commit HEAD was at when this chat started, recorded off the EDT shortly after; null until then or unknown. */
    @Volatile var startCommit: String? = null

    private val lock = Any()
    private val items = LinkedHashMap<String, ChatItem>().apply { history.forEach { put(it.id, it) } }
    @Volatile private var view: SessionView? = null
    private var pendingInsert: String? = null

    /** The name the user gave this chat (or its resumed session); null for the default name. */
    @Volatile var customTitle: String? = ChatNames.clean(spec.title)
        private set
    /** True while [customTitle] waits to be saved, as when it changed before the session had an id. */
    private val titleUnsaved = AtomicBoolean(false)
    /** Held only by the background writes, so they write in order and never block the CLI reader thread. */
    private val titleWriteLock = Any()
    @Volatile private var archived = false

    /**
     * Called when [title] changes: on the EDT when the user renames the chat, and with the session lock held, on
     * whichever thread reported the session's state, when it follows a branch move ([followBranch]).
     */
    @Volatile var onTitleChanged: (() -> Unit)? = null

    /** Called on the CLI reader thread, with the session lock held, when [cwd] changes (system/init reported another folder). */
    @Volatile var onCwdChanged: (() -> Unit)? = null
    @Volatile private var knownCwd: String? = null

    /** The header text the page shows (see [SessionView.checkout]). */
    @Volatile var checkoutText: String = ""
        private set

    /**
     * The text in the message box not sent yet, as the page last reported it: given back to a reloaded page, and kept
     * with the open chats so it survives a restart. Empty once the message is sent.
     */
    @Volatile var unsentText: String = spec.unsentText.orEmpty()

    /** True while a turn is running or waiting for the user, or background agents are working. */
    val busy: Boolean get() = synchronized(activityLock) { sessionActivity != null }

    /** Called on the CLI reader thread (or the EDT) when [busy] changes. */
    @Volatile var onBusyChanged: (() -> Unit)? = null

    @Volatile private var contextUsage: ContextUsage? = null
    @Volatile private var modelChoice: ModelChoice? = null
    /** The latest prompt suggestion, kept while the session stays idle (replayed on attach). */
    @Volatile private var suggestion: String? = null
    /** The commands this chat's agent process reported; empty until it does. */
    @Volatile private var commands: List<SlashCommand> = emptyList()
    /** The session's queued messages (replayed on attach). */
    @Volatile private var queued: List<QueuedMessage> = emptyList()
    /** The session's background processes, shown again to a view that attaches. */
    @Volatile private var processes: List<BackgroundProcess> = emptyList()
    @Volatile private var modes: List<Choice> = emptyList()
    private val draftNotices = AtomicInteger()

    private val activityLock = Any()
    private var sessionActivity: Activity? = null
    private var shownActivity: Activity? = null
    /** True while sent messages wait for their editor context (written on the EDT, read anywhere). */
    @Volatile private var sendPending = false

    /** Tells the view the activity if it changed: the session's, or Starting while a send waits for its context. */
    private fun publishActivity() {
        synchronized(activityLock) {
            val session = sessionActivity
            val now = if (sendPending && (session == null || session is Activity.Agents)) Activity.Starting else session
            if (now == shownActivity) return
            shownActivity = now
            view?.activity(now)
        }
    }

    private val listener = object : ChatListener {
        override fun onActivity(activity: Activity?) {
            val wasBusy = synchronized(activityLock) { (sessionActivity != null).also { sessionActivity = activity } }
            publishActivity()
            if (wasBusy != (activity != null)) onBusyChanged?.invoke()
        }

        override fun onContext(usage: ContextUsage) {
            if (usage == contextUsage) return
            contextUsage = usage
            view?.context(usage)
        }

        override fun onModel(choice: ModelChoice) {
            if (choice == modelChoice) return
            modelChoice = choice
            view?.model(choice)
        }

        override fun onSuggestion(text: String) {
            suggestion = text
            view?.suggestion(text)
        }

        override fun onCommands(commands: List<SlashCommand>) {
            this@Chat.commands = commands
            commandCatalog.remember(agent, commands)
            view?.commands(commands)
        }

        override fun onModes(modes: List<Choice>) {
            this@Chat.modes = modes
            view?.modes(modes)
        }

        override fun onQueue(messages: List<QueuedMessage>) {
            queued = messages
            view?.queue(messages)
        }

        override fun onProcesses(processes: List<BackgroundProcess>) {
            this@Chat.processes = processes
            view?.processes(processes)
        }

        /** Their text goes back into the message box, so a stopped turn loses nothing the user typed (images aside). */
        override fun onDropped(messages: List<QueuedMessage>) {
            val text = messages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
            if (text.isNotEmpty()) insert(text)
        }

        /**
         * Every UserText reported here is a message going out now (replayed history uses [onHistoryItem]), so the
         * conversation can be rewound to before it; marking the files is IDE work, run off this thread.
         */
        override fun onItem(item: ChatItem) {
            val shown = if (item is ChatItem.UserText && checkpoints != null) item.copy(revertable = true) else item
            synchronized(lock) { items[shown.id] = shown }
            view?.item(shown)
            if (shown is ChatItem.UserText) checkpoints?.let { c ->
                val settled = idleSince?.let { System.currentTimeMillis() - it >= Checkpoints.SETTLE_MS } == true
                background { c.mark(this@Chat, shown, settled) }
            }
        }

        override fun onHistoryItem(item: ChatItem) {
            synchronized(lock) { items[item.id] = item }
            view?.item(item)
        }

        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            if (state != SessionState.IDLE) suggestion = null
            idleSince = if (state == SessionState.IDLE) idleSince ?: System.currentTimeMillis() else null
            val running = state == SessionState.RUNNING || state == SessionState.AWAITING_PERMISSION
            if (inTurn && !running) gitNotes?.turnEnded(this@Chat)
            inTurn = running
            saveTitleIfReady()
            view?.state(state, session.modeId)
            val now = cwd
            if (now != knownCwd) {
                knownCwd = now
                onCwdChanged?.invoke()
            }
            followBranch()
        }
    }

    /**
     * True from a turn's start until it ends, so the idle state of starting or restarting the session is not taken for a
     * turn's end. Changed with the session lock held.
     */
    private var inTurn = false

    /** When the session last became idle; null while it is not. Read when a message goes out, see [Checkpoints.mark]. */
    @Volatile private var idleSince: Long? = null
    private var claudeSetup = spec.setup

    @Volatile var agent: Agent = agent
        private set

    /** False while the chat is a draft: no process, and the agent can change. */
    @Volatile var started = false
        private set

    @Volatile var session: AgentSession = newSession(agent)
        private set

    private fun newSession(agent: Agent): AgentSession {
        modes = if (agent.native) CLAUDE_MODES else emptyList()
        return agent.acp?.let { acpSession(it, listener) }
            ?: ChatSession(connector, listener, spec.cwd, spec.resumeId, claudeSetup, spec.worktree, log, ide, background, resumeAnchor, WorkingTrees())
    }

    private val predictedCwd: String? = spec.worktree?.let { "${spec.cwd.trimEnd('/')}/.claude/worktrees/$it" }

    /**
     * Absolute folder claude works in. For a new worktree this is the predicted `.claude/worktrees/<name>` until
     * system/init (which arrives only after the first message) reports the real one.
     */
    val cwd: String get() = if (session.cwdReported) session.cwd else predictedCwd ?: session.cwd

    val title: String get() = customTitle ?: defaultName

    /** The name shown while the user has given none; starts as the constructor's and follows branch moves ([followBranch]). */
    @Volatile private var defaultName: String = defaultName

    /**
     * The branch [cwd] was last seen on; null while unknown (detached, or a new worktree whose folder system/init has
     * not yet reported). A branch first seen after null counts as a move, so a new worktree's `worktree-<name>` branch
     * names it `<name>`.
     */
    @Volatile private var knownBranch: String? = if (cwdIsGuess) null else currentBranch(cwd)

    /** True while [cwd] is a new worktree's predicted folder, whose lookups would find the project's checkout instead. */
    private val cwdIsGuess: Boolean get() = predictedCwd != null && !session.cwdReported

    /**
     * Names the chat after the branch its checkout is on now, when that differs from the one last seen: a branch
     * created or switched to, or the branch of a folder the chat moved to. The default name changes even when the user
     * named the chat, so clearing that name shows the current branch; only an unnamed chat's [title] changes.
     * Detached HEAD keeps the name. Checked only when this chat's session changes state, so an idle chat on the same
     * checkout catches up with its next turn. Session lock held.
     */
    private fun followBranch() {
        if (cwdIsGuess) return
        val branch = currentBranch(cwd) ?: return
        if (branch == knownBranch) return
        knownBranch = branch
        val name = nameForBranch(WorktreeBranches.displayName(branch))
        if (name == defaultName) return
        defaultName = name
        if (customTitle == null) onTitleChanged?.invoke()
    }

    /** Renames the chat; a blank [name] restores the default. The name is saved with the session, now or once it has an id. EDT. */
    fun rename(name: String) {
        val clean = ChatNames.clean(name)
        if (clean == customTitle) return
        customTitle = clean
        onTitleChanged?.invoke()
        titleUnsaved.set(true)
        saveTitleIfReady()
    }

    /**
     * Marks the session archived (hidden from the resume list until resumed from the archived list); the caller closes
     * the chat. False before a session exists or when its agent does not use Claude's archive. EDT.
     */
    fun archive(): Boolean {
        if (!agent.native || session.sessionId == null) return false
        archived = true
        titleUnsaved.set(true)
        saveTitleIfReady()
        return true
    }

    /**
     * Saves an unsaved name once the session has an id (a new chat gets one with its first message). Any thread. The
     * write reads the name when it runs, under [titleWriteLock], so the last write is always the latest name.
     */
    private fun saveTitleIfReady() {
        if (!agent.native) return
        val save = saveTitle ?: return
        val id = session.sessionId ?: return
        if (!titleUnsaved.getAndSet(false)) return
        val folder = cwd
        background { synchronized(titleWriteLock) { save(folder, id, customTitle.orEmpty(), archived) } }
    }

    /** Starts the agent's process. A resumed chat starts when it opens, a new one with its first message. EDT. */
    fun start() {
        if (started) return
        started = true
        knownCwd = cwd
        session.start()
        view?.agent(agents, agent, true)
    }

    /** Runs [id] instead in this draft; false once started or when it is not offered. Starts no process. EDT. */
    fun selectAgent(id: String): Boolean {
        val chosen = agents.firstOrNull { it.id == id }
        if (chosen == null || started) return false
        if (chosen != agent) {
            if (agent.native) claudeSetup = session.setup
            agent = chosen
            session = newSession(chosen)
        }
        onAgentSelected(chosen)
        view?.let(::showAgent)
        return true
    }

    private fun showAgent(view: SessionView) {
        view.agent(agents, agent, started)
        view.modes(modes)
        view.state(session.state, session.modeId)
        commands.ifEmpty { commandCatalog.commandsOf(agent) }.takeIf { it.isNotEmpty() }?.let(view::commands)
    }

    /** Shows [text] in the chat. A draft numbers its own notices: the session that will run may still change. */
    fun notice(text: String, isError: Boolean = true) {
        if (started) session.notice(text, isError)
        else listener.onItem(ChatItem.Notice("draft-notice-${draftNotices.incrementAndGet()}", text, isError))
    }

    /** EDT only. */
    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        showAgent(view)
        synchronized(activityLock) { shownActivity }?.let(view::activity)
        checkoutText.takeIf { it.isNotEmpty() }?.let(view::checkout)
        contextUsage?.let(view::context)
        modelChoice?.let(view::model)
        suggestion?.let(view::suggestion)
        queued.takeIf { it.isNotEmpty() }?.let(view::queue)
        processes.takeIf { it.isNotEmpty() }?.let(view::processes)
        synchronized(lock) { pendingInsert.also { pendingInsert = null } }?.let(view::insert)
    }

    fun detach(view: SessionView) {
        if (this.view === view) this.view = null
    }

    fun insert(text: String) {
        val v = view
        if (v != null) v.insert(text) else synchronized(lock) { pendingInsert = text }
    }

    fun showCheckout(text: String) {
        if (text == checkoutText) return
        checkoutText = text
        view?.checkout(text)
    }

    /** The formatted editor context, or null when there is none or capturing it failed (a failure must never block sending). */
    private fun captureContext(format: (EditorContext) -> String): String? = try {
        editorContext(this)?.let(format)
    } catch (e: Exception) {
        if (e is ProcessCanceledException || e is ControlFlowException) throw e
        logger<Chat>().warn("Editor context capture failed; going on without it", e)
        null
    }

    /** Images waiting above this chat's message box; [send] takes them out. EDT only. */
    val attachments = PendingAttachments()

    /** This chat's images on disk, for opening in the editor: attachments are written as they are prepared. Deleted when the chat is disposed. */
    val imageFiles = ImageFiles()

    private class Outgoing(val text: String, val edits: String?, val images: List<Attachment>) {
        /** Whether [git] was looked up; the git note comes before the editor context. */
        var gitResolved = false
        var git: String? = null
    }

    /** Messages waiting for their git note and editor context, oldest first; only the head resolves, so order is kept. EDT only. */
    private val outbox = ArrayDeque<Outgoing>()
    private var resolving = false
    private var disposed = false

    /**
     * EDT only (the edit note saves documents, the context reads the editor). Ignored while the session is dead.
     * The first message starts the agent; if that fails at once, [text] goes back into the message box.
     * Claude gets the edit note, then the git note, then the editor-context note, then [text], then the [images]; the
     * chat shows [text] under the images' thumbnails. Image bytes are dropped once the line is written to the CLI;
     * each image's [Attachment.file] stays for opening it in the editor.
     * The edit note is taken now. The git note is read in the background, and when the symbol at the caret is not cached
     * it is looked up there too: the message goes out (and its bubble appears) once both are back, behind any earlier
     * message still waiting.
     */
    fun send(text: String, images: List<Attachment> = emptyList()) {
        if (session.state == SessionState.DEAD) return
        if (text.isBlank() && images.isEmpty()) return
        if (!started) {
            start()
            if (session.state == SessionState.DEAD) return insert(text)
        }
        val edits = editNote(this)
        outbox += Outgoing(text, edits, images)
        pump()
    }

    private fun pump() {
        try {
            drain()
        } finally {
            sendPending = outbox.isNotEmpty()
            publishActivity()
        }
    }

    private fun drain() {
        while (!resolving) {
            val next = outbox.firstOrNull() ?: return
            if (!next.gitResolved) {
                val notes = gitNotes?.takeIf { session.state == SessionState.IDLE }
                val resolved = notes == null || await(next, "Git note", { done -> notes.note(this, done) }) {
                    next.git = it
                    next.gitResolved = true
                }
                if (notes == null) next.gitResolved = true
                if (!resolved) return
                continue
            }
            val resolve = resolveContext
            if (resolve == null) {
                outbox.removeFirst()
                deliver(next, captureContext(ContextNotes::note))
                continue
            }
            val sent = await(next, "Editor context capture", { done -> resolve(this) { done(format(it)) } }) { note ->
                outbox.removeFirst()
                deliver(next, note)
            }
            if (!sent) return
        }
    }

    /**
     * Runs [step] for [message], the head of the outbox, and hands its result to [take]: now, returning true, when the
     * step finished before returning; else later on the EDT (while [message] still waits), followed by [pump], returning
     * false. A step that throws counts as finished with null, so a failure never blocks sending.
     */
    private fun await(message: Outgoing, what: String, step: ((String?) -> Unit) -> Unit, take: (String?) -> Unit): Boolean {
        resolving = true
        var inline = true
        var finished = false
        var result: String? = null
        val done = done@{ value: String? ->
            if (finished) return@done
            finished = true
            if (inline) {
                result = value
            } else if (stillWaiting(message)) {
                resolving = false
                take(value)
                pump()
            }
        }
        try {
            step(done)
        } catch (e: Exception) {
            if (e is ProcessCanceledException || e is ControlFlowException) throw e
            logger<Chat>().warn("$what failed; going on without it", e)
            finished = true
        }
        inline = false
        if (!finished) return false
        resolving = false
        take(result)
        return true
    }

    /** False once the outbox was cleared under [message]: the chat was closed or the turn interrupted meanwhile. */
    private fun stillWaiting(message: Outgoing): Boolean = !disposed && outbox.firstOrNull() === message

    private fun format(context: EditorContext?): String? = try {
        context?.let(ContextNotes::note)
    } catch (e: Exception) {
        if (e is ProcessCanceledException || e is ControlFlowException) throw e
        null
    }

    private fun deliver(message: Outgoing, context: String?) {
        if (disposed || session.state == SessionState.DEAD) return
        session.send(
            message.text,
            listOfNotNull(message.edits, message.git, context).joinToString("\n\n").ifEmpty { null },
            message.images.map { it.image },
            message.images.map { it.thumb },
            message.images.map { it.file },
        )
    }

    /** Puts queued message [id] back into the message box instead of sending it. */
    fun editQueued(id: String) {
        session.unqueue(id)?.text?.takeIf { it.isNotBlank() }?.let(::insert)
    }

    /** Reverts the files, the conversation or both to before message [itemId], as the user picks. EDT. */
    fun revertTo(itemId: String) {
        val item = synchronized(lock) { items[itemId] } as? ChatItem.UserText ?: return
        if (!item.revertable) return
        val choice = checkpoints?.revert(this, item, session.canRewindTo(itemId)) ?: return
        if (choice.conversation) rewindTo(item, choice.files)
    }

    /**
     * Claude forgets [item] and what followed; the panel drops them and the message goes back into the input box. What
     * to drop is decided before the rewind, which may add a notice (claude failing to start) that must stay. The fork
     * gets a new session id, so a name the user gave is marked unsaved and saved under it once claude reports it.
     */
    private fun rewindTo(item: ChatItem.UserText, filesReverted: Boolean) {
        val dropped = synchronized(lock) {
            val ids = items.keys.toList()
            val at = ids.indexOf(item.id)
            if (at < 0) null else ids.subList(at, ids.size)
        } ?: return
        if (!session.rewind(item.id)) {
            session.notice(
                if (filesReverted) "The conversation could not be taken back: Claude started working."
                else "Couldn't take the conversation back: Claude started working.")
            return
        }
        val kept = synchronized(lock) {
            dropped.forEach(items::remove)
            items.values.toList()
        }
        view?.reset(kept)
        if (customTitle != null) titleUnsaved.set(true)
        checkpoints?.conversationRewound(this, item, filesReverted)
        item.text.takeIf { it.isNotBlank() }?.let(::insert)
    }

    /** Stops the turn, and drops messages still waiting for their git note or editor context. EDT only. */
    fun interrupt() {
        if (outbox.isNotEmpty()) {
            outbox.clear()
            resolving = false
            sendPending = false
        }
        session.interrupt()
        publishActivity()
    }

    fun dispose() {
        disposed = true
        outbox.clear()
        sendPending = false
        attachments.clear()
        session.dispose()
        imageFiles.delete()
    }
    private companion object {
        val CLAUDE_MODES = PermissionMode.entries.map { Choice(it.cli, it.label) }
    }
}
