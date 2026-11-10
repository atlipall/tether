package tether.acp

import tether.cli.CliConnection
import tether.cli.ContextUsage
import tether.image.Image
import tether.session.Activity
import tether.session.AgentSession
import tether.session.ChatItem
import tether.session.SentImageFiles
import tether.session.ChatListener
import tether.session.Choice
import tether.session.FileDiff
import tether.session.PermissionDecision
import tether.session.QueuedMessage
import tether.session.SessionSetup
import tether.session.SessionState
import tether.session.ToolStatus
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/**
 * One chat with an ACP agent in [cwd]. Each start makes a new client (one agent process); a restart loads the same
 * session where the agent can. A supplied [resumeId] loads its history into a fresh chat; later reconnects omit the
 * already-shown replay. [name] names the agent in notices. [mode] is the mode the session was left in, asked for again
 * whenever the session opens. Thread-safe.
 */
class AcpSession(
    private val newClient: () -> AcpClient,
    private val listener: ChatListener,
    override val cwd: String,
    private val name: String,
    private val log: (String) -> Unit = {},
    resumeId: String? = null,
    mode: String? = null,
) : AgentSession {
    private val lock = Any()

    override val cwdReported: Boolean get() = true
    override val setup: SessionSetup get() = SessionSetup()

    @Volatile override var sessionId: String? = resumeId
        private set
    @Volatile override var modeId: String? = mode
        private set
    @Volatile override var state: SessionState = SessionState.IDLE
        private set

    private var client: AcpClient? = null
    private var agent: AgentInfo? = null
    private var generation = 0
    private var seq = 0
    private var modes: List<Choice> = emptyList()
    /** True once the session is open; messages sent before wait in the queue. */
    private var ready = false
    /** True while session/load replays history. */
    private var loading = false
    /** True after the first successful open, whose history is already shown when reconnecting. */
    private var openedOnce = false
    /** The mode to ask for once the session opens: the one it was in when [start] began, before any history replay. */
    private var wantedMode: String? = null
    /** The running prompt; null between turns. */
    private var turn: CompletableFuture<String>? = null
    private var turnStarted = 0L
    private var interruptRequested = false

    /** A message waiting to go out, with its images' bytes and their [files] on disk, kept for [imageFile] once it goes out. */
    private class Queued(val shown: QueuedMessage, val text: String, val images: List<Image>, val files: List<String>)
    private val imageFiles = SentImageFiles()
    private val queue = ArrayDeque<Queued>()
    /** [live]: the card's tool call was already running when the agent asked. */
    private class Pending(val request: PermissionRequest, val toolId: String, val live: Boolean)
    private val pending = LinkedHashMap<String, Pending>()
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()
    /** Tool calls started and not yet finished (id to title), oldest first. */
    private val runningTools = LinkedHashMap<String, String>()
    /** The user message being replayed, accumulated across text chunks. */
    private var replayedUser: ChatItem.UserText? = null
    private var replayedUserMessage: String? = null
    private var streaming: ChatItem.AssistantText? = null
    private var streamingMessage: String? = null
    private var phase: Activity? = null
    private var reported: Activity? = null

    private val prompting get() = turn != null

    override fun start() {
        synchronized(lock) {
            val gen = ++generation
            wantedMode = modeId
            val c = try {
                newClient()
            } catch (e: Exception) {
                emit(ChatItem.Notice(nextId("notice"), e.message ?: e.toString(), isError = true))
                setState(SessionState.DEAD)
                return
            }
            client = c
            ready = false
            listener.onModes(modes)
            setState(SessionState.IDLE)
            c.connect(events(gen))
                .thenCompose { info -> synchronized(lock) { open(c, info) } }
                .whenComplete { opened, error -> synchronized(lock) { if (gen == generation) opened(c, opened, error) } }
        }
    }

    private fun open(c: AcpClient, info: AgentInfo): CompletableFuture<SessionOpened> {
        agent = info
        val previous = sessionId ?: return c.newSession(cwd)
        if (!info.loadSession) {
            emit(ChatItem.Notice(nextId("notice"), "$name can't reopen its sessions; this is a new session.", isError = false))
            return c.newSession(cwd)
        }
        loading = true
        return c.loadSession(previous, cwd)
    }

    /**
     * Finishes [start] once the session is open, or reports why it could not open. When the agent's process ended,
     * [closed] reports it instead, with the exit code and the agent's output.
     */
    private fun opened(c: AcpClient, opened: SessionOpened?, error: Throwable?) {
        if (opened == null && error?.let(::unwrap) is ConnectionLost) return
        val reloaded = loading && openedOnce
        closeText()
        loading = false
        replayedUser = null
        if (opened == null) {
            generation++
            c.close()
            client = null
            emit(ChatItem.Notice(nextId("notice"), "Couldn't start $name: ${messageOf(error)}", isError = true))
            dropQueue()
            setState(SessionState.DEAD)
            return
        }
        sessionId = opened.sessionId
        modes = opened.modes
        modeId = opened.currentMode
        openedOnce = true
        listener.onModes(modes)
        notifyState()
        if (reloaded) emit(ChatItem.Notice(nextId("notice"), "Reconnected to the session.", isError = false))
        restoreMode(c, wantedMode, ::becomeReady)
    }

    /**
     * Asks the agent to go back to [wanted], the mode before this open, then calls [then]; messages sent meanwhile wait
     * in the queue so they run in that mode. Skipped when the agent is already in it or no longer offers it. When the
     * agent refuses, the session keeps the agent's mode and says so.
     */
    private fun restoreMode(c: AcpClient, wanted: String?, then: () -> Unit) {
        val id = sessionId
        if (wanted == null || wanted == modeId || id == null || modes.none { it.id == wanted }) return then()
        val gen = generation
        c.setMode(id, wanted).whenComplete { _, error ->
            synchronized(lock) {
                if (gen != generation) return@synchronized
                if (error == null) modeId = wanted
                else emit(ChatItem.Notice(nextId("notice"), "Couldn't restore the mode: ${messageOf(error)}", isError = true))
                notifyState()
                then()
            }
        }
    }

    /** The session is open and in its mode: messages go out, starting with any queued while it opened. */
    private fun becomeReady() {
        ready = true
        sendNext()
    }

    private fun events(gen: Int) = object : AcpListener {
        override fun onUpdate(sessionId: String, update: AcpUpdate) {
            synchronized(lock) { if (gen == generation && (!loading || !openedOnce)) handle(update) }
        }

        override fun onPermission(request: PermissionRequest) {
            synchronized(lock) { if (gen == generation) ask(request) else request.cancel() }
        }

        override fun onClosed(exitCode: Int?, detail: String) {
            synchronized(lock) { if (gen == generation) closed(exitCode, detail) }
        }
    }

    override fun send(text: String, prefix: String?, images: List<Image>, thumbs: List<String>, files: List<String>) {
        synchronized(lock) {
            if (state == SessionState.DEAD) return
            val payload = listOfNotNull(prefix?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotEmpty() }).joinToString("\n\n")
            val message = Queued(QueuedMessage(nextId("queued"), text, thumbs), payload, images, files)
            if (ready && state == SessionState.IDLE) {
                prompt(message)
            } else {
                queue.addLast(message)
                notifyQueue()
            }
        }
    }

    override fun imageFile(itemId: String, index: Int): String? = synchronized(lock) {
        imageFiles.get(itemId, index)
    }

    override fun toolCall(id: String): ChatItem.ToolCall? = synchronized(lock) { toolCalls[id] }

    private fun prompt(message: Queued) {
        val c = client ?: return
        val id = sessionId ?: return
        val item = ChatItem.UserText(nextId("user"), message.shown.text, message.shown.images)
        imageFiles.keep(item.id, message.files)
        emit(item)
        val images = message.images.takeIf { agent?.images == true }.orEmpty()
        val dropped = message.images.size - images.size
        if (dropped > 0) {
            val count = if (dropped == 1) "1 image was" else "$dropped images were"
            emit(ChatItem.Notice(nextId("notice"), "$name does not accept images; $count not sent.", isError = true))
        }
        turnStarted = System.currentTimeMillis()
        val gen = generation
        val future = c.prompt(id, message.text, images)
        turn = future
        beginRunning(Activity.Starting)
        reportActivity()
        future.whenComplete { stopReason, error ->
            synchronized(lock) { if (gen == generation && turn === future) turnEnded(stopReason, error) }
        }
    }

    private fun turnEnded(stopReason: String?, error: Throwable?) {
        val cause = error?.let(::unwrap)
        if (cause is ConnectionLost) return
        turn = null
        closeText()
        cancelPermissions()
        val completed = stopReason == END_TURN
        finishRunningTools(if (completed) ToolStatus.DONE else ToolStatus.ERROR)
        phase = null
        val duration = System.currentTimeMillis() - turnStarted
        emit(when {
            interruptRequested || stopReason == CANCELLED -> ChatItem.Notice(nextId("notice"), "Interrupted", isError = false)
            cause != null -> ChatItem.TurnResult(nextId("result"), true, cause.message ?: cause.toString(), duration, null)
            completed -> ChatItem.TurnResult(nextId("result"), false, null, duration, null)
            else -> ChatItem.TurnResult(nextId("result"), true, "Stopped: $stopReason", duration, null)
        })
        interruptRequested = false
        sendNext()
        reportActivity()
    }

    /** Marks every tool call still running as [status] and forgets them. */
    private fun finishRunningTools(status: ToolStatus) {
        for (id in runningTools.keys) toolCalls[id]?.let { upsertTool(it.copy(status = status)) }
        runningTools.clear()
    }

    private fun sendNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            setState(SessionState.IDLE)
            return
        }
        notifyQueue()
        prompt(next)
    }

    private fun handle(update: AcpUpdate) {
        if (update !is AcpUpdate.UserText) replayedUser = null
        when (update) {
            is AcpUpdate.UserText -> if (loading) {
                closeText()
                val current = replayedUser?.takeIf { replayedUserMessage == update.messageId }
                val next = (current ?: ChatItem.UserText(nextId("user"), "")).let { it.copy(text = it.text + update.text) }
                replayedUser = next
                replayedUserMessage = update.messageId
                emit(next)
            }
            is AcpUpdate.AgentText -> {
                val current = streaming?.takeIf { streamingMessage == update.messageId }
                if (current == null) closeText()
                val next = (current ?: ChatItem.AssistantText(nextId("text"), "", true)).let { it.copy(text = it.text + update.text) }
                streaming = next
                streamingMessage = update.messageId
                emit(next)
                phase = Activity.Writing
            }
            AcpUpdate.Thought -> phase = Activity.Thinking
            is AcpUpdate.Tool -> {
                closeText()
                val tool = merged(update)
                val status = when (update.status) {
                    "pending", "in_progress" -> ToolStatus.RUNNING
                    "completed" -> ToolStatus.DONE
                    "failed" -> ToolStatus.ERROR
                    else -> tool.status
                }
                upsertTool(tool.copy(status = status))
                if (status == ToolStatus.RUNNING) runningTools[tool.id] = tool.name else runningTools.remove(tool.id)
                phase = toolPhase()
            }
            is AcpUpdate.Mode -> {
                modeId = update.id
                notifyState()
            }
            is AcpUpdate.Usage -> if (update.size > 0) {
                val percent = (update.used * 100 / update.size).toInt().coerceIn(0, 100)
                listener.onContext(ContextUsage(update.used, update.size, percent, null, emptyList()))
            }
            is AcpUpdate.Commands -> listener.onCommands(update.commands)
            is AcpUpdate.Other -> log("ACP update not shown: ${update.kind}")
        }
        reportActivity()
    }

    private fun ask(request: PermissionRequest) {
        closeText()
        val key = nextId("perm")
        val tool = merged(request.tool)
        pending[key] = Pending(request, tool.id, live = tool.id in toolCalls)
        upsertTool(tool.copy(
            status = ToolStatus.AWAITING_PERMISSION,
            permissionRequestId = key,
            choices = request.options.map { Choice(it.id, it.name) },
        ))
        settle()
        reportActivity()
    }

    /** The row for [update]'s tool call with the fields it carries applied. */
    private fun merged(update: AcpUpdate.Tool): ChatItem.ToolCall {
        val base = toolCalls[update.id]
            ?: ChatItem.ToolCall(update.id, update.title ?: "tool", EMPTY, null, ToolStatus.RUNNING, null, null, false)
        return base.copy(
            name = update.title ?: base.name,
            input = update.input ?: base.input,
            filePath = update.path ?: base.filePath,
            resultPreview = update.text?.take(MAX_PREVIEW) ?: base.resultPreview,
        )
    }

    override fun respond(requestId: String, decision: PermissionDecision) {
        synchronized(lock) {
            if (decision !is PermissionDecision.Option) {
                log("An ACP permission is answered with one of the agent's options, not $decision")
                return
            }
            val p = pending[requestId] ?: return
            val option = p.request.options.firstOrNull { it.id == decision.optionId } ?: return
            pending.remove(requestId)
            p.request.select(option.id)
            val status = when {
                option.kind.startsWith("reject") -> ToolStatus.DENIED
                p.live -> ToolStatus.RUNNING
                else -> ToolStatus.DONE
            }
            if (status != ToolStatus.RUNNING) runningTools.remove(p.toolId)
            toolCalls[p.toolId]?.let { upsertTool(it.copy(status = status, permissionRequestId = null)) }
            phase = toolPhase()
            settle()
            reportActivity()
        }
    }

    /** Answers every open permission request `cancelled`, as ACP asks of a client that stops a turn. */
    private fun cancelPermissions() {
        for (p in pending.values) {
            p.request.cancel()
            runningTools.remove(p.toolId)
            toolCalls[p.toolId]?.let { upsertTool(it.copy(status = ToolStatus.DENIED, permissionRequestId = null)) }
        }
        pending.clear()
    }

    override fun setMode(id: String) {
        synchronized(lock) {
            val c = client ?: return
            val session = sessionId ?: return
            if (!ready || modes.none { it.id == id }) return
            val gen = generation
            c.setMode(session, id).whenComplete { _, error ->
                synchronized(lock) {
                    if (gen != generation) return@synchronized
                    if (error == null) modeId = id
                    else emit(ChatItem.Notice(nextId("notice"), "Couldn't change the mode: ${messageOf(error)}", isError = true))
                    notifyState()
                }
            }
        }
    }

    override fun unqueue(id: String): QueuedMessage? = synchronized(lock) {
        val i = queue.indexOfFirst { it.shown.id == id }
        if (i < 0) return null
        val removed = queue.removeAt(i).shown
        notifyQueue()
        removed
    }

    override fun sendNow(id: String) {
        synchronized(lock) {
            if (!prompting) return
            val i = queue.indexOfFirst { it.shown.id == id }
            if (i < 0) return
            if (i > 0) {
                queue.addFirst(queue.removeAt(i))
                notifyQueue()
            }
            if (!interruptRequested) stopTurn()
        }
    }

    override fun interrupt() {
        synchronized(lock) {
            if (!prompting) return
            dropQueue()
            if (!interruptRequested) stopTurn()
        }
    }

    /** The turn stays open until the agent answers the prompt (stop reason `cancelled`). */
    private fun stopTurn() {
        interruptRequested = true
        cancelPermissions()
        sessionId?.let { client?.cancel(it) }
        settle()
        reportActivity()
    }

    override fun notice(text: String, isError: Boolean) {
        synchronized(lock) { emit(ChatItem.Notice(nextId("notice"), text, isError)) }
    }

    override fun restart() {
        synchronized(lock) {
            if (state != SessionState.DEAD) return
            start()
        }
    }

    /** Cancels an active turn before closing, waiting at most [CLOSE_WAIT_SECONDS] for its acknowledgement. */
    override fun dispose() {
        synchronized(lock) {
            generation++
            val c = client ?: return
            client = null
            val running = turn
            val id = sessionId
            if (running == null || id == null) {
                c.close()
                return
            }
            pending.values.forEach { it.request.cancel() }
            pending.clear()
            c.cancel(id)
            running.whenComplete { _, _ -> c.close() }
            CompletableFuture.delayedExecutor(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS).execute(c::close)
        }
    }

    override fun diffFor(toolUseId: String): FileDiff? = null

    /**
     * The agent's process ended: the chat goes dead and says why. Permission cards still waiting are marked denied and
     * tool calls still running failed, so none of them looks live in a dead chat.
     */
    private fun closed(exitCode: Int?, detail: String) {
        client = null
        ready = false
        loading = false
        turn = null
        interruptRequested = false
        cancelPermissions()
        finishRunningTools(ToolStatus.ERROR)
        replayedUser = null
        closeText()
        dropQueue()
        phase = null
        emit(ChatItem.Notice(nextId("notice"), CliConnection.exitNotice(name, exitCode, detail), isError = true))
        setState(SessionState.DEAD)
        reportActivity()
    }

    private fun dropQueue() {
        if (queue.isEmpty()) return
        val dropped = queue.map { it.shown }
        queue.clear()
        notifyQueue()
        listener.onDropped(dropped)
        val images = dropped.sumOf { it.images.size }
        if (images > 0) emit(ChatItem.Notice(nextId("notice"), "$images queued image${if (images == 1) " was" else "s were"} not sent.", isError = false))
    }

    private fun closeText() {
        streaming?.let { emit(it.copy(streaming = false)) }
        streaming = null
        streamingMessage = null
    }

    private fun toolPhase(): Activity = runningTools.entries.lastOrNull()?.let { Activity.Running(it.value) } ?: Activity.Thinking

    private fun settle() = setState(when {
        pending.isNotEmpty() -> SessionState.AWAITING_PERMISSION
        prompting -> SessionState.RUNNING
        else -> SessionState.IDLE
    })

    private fun reportActivity() {
        val now = if (!prompting) null else if (pending.isNotEmpty()) Activity.Waiting else phase
        if (now == reported) return
        reported = now
        listener.onActivity(now)
    }

    private fun beginRunning(phase: Activity) {
        this.phase = phase
        setState(SessionState.RUNNING)
    }

    private fun nextId(prefix: String) = "$prefix-${++seq}"
    private fun upsertTool(tool: ChatItem.ToolCall) { toolCalls[tool.id] = tool; emit(tool) }
    private fun emit(item: ChatItem) {
        if (loading && !openedOnce) listener.onHistoryItem(item) else listener.onItem(item)
    }
    private fun setState(newState: SessionState) { state = newState; notifyState() }
    private fun notifyState() = listener.onState(state, sessionId, null)
    private fun notifyQueue() = listener.onQueue(queue.map { it.shown })

    private companion object {
        const val END_TURN = "end_turn"
        const val CANCELLED = "cancelled"
        const val MAX_PREVIEW = 4000
        const val CLOSE_WAIT_SECONDS = 3L
        val EMPTY = JsonObject(emptyMap())

        fun unwrap(error: Throwable): Throwable = if (error is CompletionException) error.cause ?: error else error
        fun messageOf(error: Throwable?): String = error?.let(::unwrap)?.let { it.message ?: it.toString() } ?: "unknown error"
    }
}
