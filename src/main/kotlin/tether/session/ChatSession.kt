package tether.session

import tether.cli.BackgroundTask
import tether.cli.CliConnection
import tether.cli.CliEvent
import tether.cli.CliMessages
import tether.cli.CliParser
import tether.cli.ContextUsage
import tether.cli.HookRegistration
import tether.cli.McpServer
import tether.cli.ModelChoice
import tether.cli.ModelOption
import tether.cli.Models
import tether.cli.PermissionMode
import tether.cli.RunningModel
import tether.cli.SessionLaunch
import tether.cli.SlashCommand
import tether.cli.SlashCommands
import tether.cli.obj
import tether.cli.str
import tether.cli.ImageBlock
import tether.git.TreeSnapshot
import tether.git.WorkingTrees
import tether.image.Image
import tether.image.Images
import kotlinx.serialization.json.JsonObject
import java.util.Base64

enum class SessionState { IDLE, RUNNING, AWAITING_PERMISSION, DEAD }

/** What Claude is doing during a turn, as far as the CLI's events tell. */
sealed interface Activity {
    /** The message was handed over; nothing has come back yet. */
    data object Starting : Activity
    data object Thinking : Activity
    data class Running(val tool: String) : Activity
    data object Writing : Activity
    /** A permission request or question is open. */
    data object Waiting : Activity
    /**
     * No turn is running, but [count] background tasks (subagents, shell commands, monitors) still are. The chat stays
     * busy; the page shows them as task rows rather than in the activity strip.
     */
    data class Agents(val count: Int) : Activity
}

/** Called while the session lock is held: do not call back into the session synchronously. */
interface ChatListener {
    fun onItem(item: ChatItem)

    /** A restored ACP history item, not a newly sent message; defaults to ordinary item delivery. */
    fun onHistoryItem(item: ChatItem) = onItem(item)

    fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?)

    /** The modes an ACP agent offers; empty when it offers none to choose from. */
    fun onModes(modes: List<Choice>) {}

    /** The turn's activity, reported only when it changes; null when nothing is running (no turn, no background agent). */
    fun onActivity(activity: Activity?) {}

    /** How full the context window is (the latest report). */
    fun onContext(usage: ContextUsage) {}

    /** The model and effort in use and what can be picked, after each change; never told by a CLI that can't say. */
    fun onModel(choice: ModelChoice) {}

    /** A predicted next prompt, told only while the session is idle. */
    fun onSuggestion(text: String) {}

    /** The slash commands the agent offers, each time it reports them (claude does once per process). */
    fun onCommands(commands: List<SlashCommand>) {}

    /** The messages waiting for the running turn to end, oldest first (every change). */
    fun onQueue(messages: List<QueuedMessage>) {}

    /** The background processes running now, oldest first (every change). */
    fun onProcesses(processes: List<BackgroundProcess>) {}

    /** Queued messages that will not be sent (the turn was stopped or the process died), oldest first. */
    fun onDropped(messages: List<QueuedMessage>) {}
}

/** A message sent while a turn was running; [images] are thumbnails as for [ChatItem.UserText]. */
data class QueuedMessage(val id: String, val text: String, val images: List<String>)

sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data object AllowForSession : PermissionDecision
    data class Deny(val message: String) : PermissionDecision
    /** One of the answers an ACP agent offered ([ChatItem.ToolCall.choices]). */
    data class Option(val optionId: String) : PermissionDecision
}

data class FileDiff(val filePath: String, val originalContent: String, val isNew: Boolean = false)

/** Longest base64 string accepted for a tool-result image (about 5 MB of bytes); longer ones show as "[image]". */
private const val MAX_TOOL_IMAGE_BASE64 = 7_000_000

/** Most turns whose file diffs are kept; older turns' lists still show but no longer open diffs. */
private const val MAX_TURN_DIFFS = 50

/** Name of the in-process MCP server that serves [IdeHost.tools]; Claude sees its tools as `mcp__tether__<name>`. */
internal const val IDE_SERVER = "tether"
private const val IDE_TOOL_PREFIX = "mcp__${IDE_SERVER}__"

/** Callback id of the PostToolUse hook that runs [IdeHost.afterEdit]. */
private const val AFTER_EDIT = "after-edit"
private const val POST_TOOL_USE = "PostToolUse"
private val EDIT_TOOLS = HookRegistration("Edit|Write|MultiEdit|NotebookEdit", AFTER_EDIT, timeoutSeconds = 60)

/** Callback ids of the hooks around a Bash call that snapshot the checkout, so the files it changed join the turn's list. */
private const val BEFORE_BASH = "before-bash"
private const val AFTER_BASH = "after-bash"
private const val PRE_TOOL_USE = "PreToolUse"
private const val POST_TOOL_USE_FAILURE = "PostToolUseFailure"
private const val BASH_SECONDS = 60
private val BASH_HOOKS = listOf(
    PRE_TOOL_USE to HookRegistration("Bash", BEFORE_BASH, BASH_SECONDS),
    POST_TOOL_USE to HookRegistration("Bash", AFTER_BASH, BASH_SECONDS),
    POST_TOOL_USE_FAILURE to HookRegistration("Bash", AFTER_BASH, BASH_SECONDS),
)

/** Prefix of get_context_usage request ids, so their replies are recognised even after a newer request replaced them. */
private const val CONTEXT_PREFIX = "context"

/** Prefix of get_settings request ids, whose replies say which model and effort are in use. */
private const val SETTINGS_PREFIX = "settings"

/** Most images of one tool result converted to display copies; further ones show as "[image]". */
private const val MAX_TOOL_IMAGES = 6
/** Longest agent prompt or report kept; longer ones are cut and end with "…". */
internal const val MAX_AGENT_TEXT = 100_000

/** The task type of a subagent; every other type is a [BackgroundProcess]. */
private const val AGENT_TASK = "local_agent"

/** The label of a background task's row: its tool's name where there is one, else the CLI's type. */
private fun processKind(type: String): String = when (type) {
    "local_bash" -> "Bash"
    "monitor" -> "Monitor"
    "local_workflow" -> "Workflow"
    else -> type
}

/** The call a subagent ends with; its input is the report, which the card shows from the task events instead. */
private const val HANDBACK = "SubagentHandback"

private fun capped(text: String?) = text?.let { if (it.length > MAX_AGENT_TEXT) it.take(MAX_AGENT_TEXT) + "\u2026" else it }

/** Longest prompt suggestion passed on; longer ones are dropped rather than cut, since a cut prompt is a wrong one. */
internal const val MAX_SUGGESTION_CHARS = 2000

/** One conversation with one claude process (respawned with --resume on restart). Thread-safe. */
class ChatSession(
    private val connect: (SessionLaunch) -> CliConnection,
    private val listener: ChatListener,
    cwd: String,
    resumeId: String? = null,
    /** The permission mode, model and effort to start with; null parts are left to the CLI's settings. */
    setup: SessionSetup = SessionSetup(),
    private val worktree: String? = null,
    private val log: (String) -> Unit = {},
    /** Tools and edit checks the IDE lends Claude; null lends none. */
    private val ide: IdeHost? = null,
    /** Runs IDE work (tool calls, edit checks) off the CLI reader thread. */
    private val background: (Runnable) -> Unit = { it.run() },
    /** The uuid of the resumed transcript's newest assistant message: where a rewind to its first new message resumes. */
    resumeAnchor: String? = null,
    /** Snapshots the checkout around Bash calls so the files they change show in the turn's list; null skips that. */
    private val trees: WorkingTrees? = null,
    /** The time in epoch milliseconds, for when a background process started. */
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentSession {
    private val lock = Any()

    /** The CLI's session id once known; also read by the IDE's save thread (see OpenChats). */
    @Volatile override var sessionId: String? = resumeId
        private set
    /** The permission mode the session runs in; also read by the IDE's save thread (see OpenChats). */
    @Volatile var mode: PermissionMode? = setup.mode
        private set
    override val modeId: String? get() = mode?.cli

    /** Where the session is in its lifecycle; written under [lock], also read from the EDT without it. */
    @Volatile override var state: SessionState = SessionState.IDLE
        private set
    /** The model the user picked for this chat, passed to every new process; null for the CLI's own choice. Also read by the save thread. */
    @Volatile var model: String? = setup.model
        private set
    /** The effort level the user picked, likewise. */
    @Volatile var effort: String? = setup.effort
        private set

    /** What this chat runs with now, to reopen it the same way. */
    override val setup: SessionSetup get() = SessionSetup(mode, model, effort)

    /** The folder claude runs in: the launch directory until system/init reports the real one (a new worktree's path). */
    @Volatile override var cwd: String = cwd
        private set
    /** True once system/init reported a cwd; from then on restarts run there instead of creating the worktree again. */
    @Volatile override var cwdReported = false
        private set

    private var connection: CliConnection? = null
    private var generation = 0
    private var seq = 0
    private var interruptRequested = false
    /**
     * A queued message, its line for the CLI's stdin (image bytes live only here until sent) and its images' files,
     * kept for [imageFile] once it goes out.
     */
    private class Queued(val shown: QueuedMessage, val line: String, val files: List<String>)
    /** Messages waiting for the running turn to end, oldest first. */
    private val queue = ArrayDeque<Queued>()
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()
    private val streamed = HashMap<String, ChatItem.AssistantText>()
    private val streamedMessages = HashSet<String>()
    /** The initialize request of the current process; the first context-usage request waits until it has been answered. */
    private var initRequestId: String? = null
    /** The latest get_context_usage request; replies to older ones are stale and dropped. */
    private var contextRequestId: String? = null
    /** The latest get_settings request; replies to older ones are stale and dropped. */
    private var settingsRequestId: String? = null
    /** The models the current process offers; empty until its initialize reply (and for a CLI that lists none). */
    private var modelOptions: List<ModelOption> = emptyList()
    /** Model and effort changes waiting for their reply, by request id, each with what puts the old choice back. */
    private val pendingChoices = HashMap<String, () -> Unit>()
    private val pendingPermissions = LinkedHashMap<String, CliEvent.PermissionRequest>()
    private val deniedToolUses = HashSet<String>()
    private val sessionAllowedTools = HashSet<String>()
    private val diffs = HashMap<String, FileDiff>()
    private val imageFiles = SentImageFiles()
    /** The running turn's edits; summed up on its [ChatItem.TurnResult]. */
    private val turnEdits = TurnEdits()
    /** The checkout before each running Bash call, by its tool use id. */
    private val snapshotsBeforeBash = HashMap<String, TreeSnapshot>()
    /** Each recent turn's file diffs, by its result item's id, oldest first. */
    private val turnDiffs = object : LinkedHashMap<String, List<FileDiff>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileDiff>>) = size > MAX_TURN_DIFFS
    }
    /** What is known about each subagent, by its Agent call's id (task events may arrive before the call's item). */
    private val agents = HashMap<String, AgentInfo>()
    /** Tool calls started and not yet answered (id to name), oldest first. */
    private val runningTools = LinkedHashMap<String, String>()
    /** The phase of the running turn, null between turns; [Activity.Waiting] is derived from the open requests. */
    private var phase: Activity? = null
    private var reported: Activity? = null
    /** Background tasks the CLI reports running; they outlive the turn that started them. */
    private var backgroundTasks: List<BackgroundTask> = emptyList()
    /** The background processes last told to the listener. */
    private var processes: List<BackgroundProcess> = emptyList()
    /** Calls whose task is not a subagent (a backgrounded Bash call), so their task events don't make them agent cards. */
    private val otherTasks = HashSet<String>()
    /** Serves [IdeHost.tools] to the current process; null when it was started without them. */
    private var mcpServer: McpServer? = null
    /** Full names (`mcp__tether__<name>`) of the current process's read-only IDE tools, allowed without asking. */
    private var readOnlyTools: Set<String> = emptySet()
    /** The newest main-conversation assistant message's uuid: where a rewind to the next message would resume. */
    private var lastAnswer: String? = resumeAnchor
    /** Each message this session sent, by item id, to the answer it followed (null: it was the first), oldest first. */
    private val anchors = LinkedHashMap<String, String?>()
    /** Where to resume [from] until the forked session reports its id; null when not rewinding. */
    private class Fork(val from: String, val at: String)
    private var fork: Fork? = null

    /**
     * Starts the claude process and sends it the initialize request; a failed launch shows a notice and leaves the
     * session dead. Each output line is parsed, and its images decoded, on the reader thread before the lock is taken,
     * as that can be slow; events from an earlier process are dropped.
     */
    override fun start() {
        synchronized(lock) {
            val gen = ++generation
            var created: CliConnection? = null
            try {
                val f = fork
                val conn = connect(SessionLaunch(f?.from ?: sessionId, mode, cwd, if (cwdReported) null else worktree, f?.at, f != null, model, effort))
                created = conn
                connection = conn
                conn.start(
                    onLine = { line ->
                        val events = CliParser.parse(line).map { it to displayCopiesOf(it) }
                        synchronized(lock) { if (gen == generation) for ((event, copies) in events) handle(event, copies) }
                    },
                    onExit = { code, tail -> synchronized(lock) { if (gen == generation) handleExit(code, tail) } },
                )
                val initId = nextId("init")
                initRequestId = initId
                val tools = ide?.tools.orEmpty()
                mcpServer = if (tools.isEmpty()) null else McpServer(IDE_SERVER, tools)
                readOnlyTools = tools.filter { it.readOnly }.mapTo(HashSet()) { IDE_TOOL_PREFIX + it.name }
                val hooks = buildList {
                    if (ide?.checksEdits == true) add(POST_TOOL_USE to EDIT_TOOLS)
                    if (trees != null) addAll(BASH_HOOKS)
                }.groupBy({ it.first }, { it.second })
                conn.send(CliMessages.initialize(initId, listOfNotNull(mcpServer?.let { IDE_SERVER }), hooks))
            } catch (e: Exception) {
                generation++
                created?.let { runCatching { it.stop() } }
                connection = null
                emit(ChatItem.Notice(nextId("notice"), e.message ?: e.toString(), isError = true))
                setState(SessionState.DEAD)
                return
            }
            setState(SessionState.IDLE)
        }
    }

    /**
     * Shows [text] (with [thumbs] of its images above it) in the chat; sends "[prefix]\n\n[text]" and [images] to
     * Claude. [files] are the images' paths on disk, kept for [imageFile]. While a turn is running the message
     * is queued instead (see [ChatListener.onQueue]) and enters the chat when it goes out.
     */
    override fun send(
        text: String,
        prefix: String?,
        images: List<Image>,
        thumbs: List<String>,
        files: List<String>,
    ) {
        synchronized(lock) {
            if (state == SessionState.DEAD) return
            val payload = listOfNotNull(prefix?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotEmpty() }).joinToString("\n\n")
            val line = CliMessages.userContent(payload, images)
            if (state == SessionState.IDLE) {
                val item = ChatItem.UserText(nextId("user"), text, thumbs)
                imageFiles.keep(item.id, files)
                anchors[item.id] = lastAnswer
                emit(item)
                connection?.send(line)
                beginRunning(Activity.Starting)
                reportActivity()
            } else {
                queue.addLast(Queued(QueuedMessage(nextId("queued"), text, thumbs), line, files))
                notifyQueue()
            }
        }
    }

    /** Takes queued message [id] out of the queue without sending it; null when it is not queued (any more). */
    override fun unqueue(id: String): QueuedMessage? = synchronized(lock) {
        val i = queue.indexOfFirst { it.shown.id == id }
        if (i < 0) return null
        val removed = queue.removeAt(i).shown
        notifyQueue()
        removed
    }

    /** Stops the running turn and sends queued message [id] next; the rest of the queue follows it. */
    override fun sendNow(id: String) {
        synchronized(lock) {
            if (!turnOpen) return
            val i = queue.indexOfFirst { it.shown.id == id }
            if (i < 0) return
            if (i > 0) {
                queue.addFirst(queue.removeAt(i))
                notifyQueue()
            }
            if (!interruptRequested) stopTurn()
        }
    }

    /** Shows a notice in the chat (e.g. an image that could not be attached). Any thread. */
    override fun notice(text: String, isError: Boolean) {
        synchronized(lock) { emit(ChatItem.Notice(nextId("notice"), text, isError)) }
    }

    /**
     * Settles permission request [requestId] with [decision]. A question is answered with [answer] or denied; allowing
     * it is ignored, as it can't be allowed without answers. ACP-only choices are ignored without settling the request.
     */
    override fun respond(requestId: String, decision: PermissionDecision) {
        synchronized(lock) {
            if (decision is PermissionDecision.Option) return
            val pending = pendingPermissions[requestId] ?: return
            if (pending.toolName == ASK_USER_QUESTION && decision !is PermissionDecision.Deny) return
            val req = pendingPermissions.remove(requestId) ?: return
            val toolId = req.toolUseId ?: "perm-$requestId"
            when (decision) {
                PermissionDecision.Allow -> connection?.send(CliMessages.allow(requestId, req.input))
                PermissionDecision.AllowForSession -> {
                    sessionAllowedTools += req.toolName
                    connection?.send(CliMessages.allow(requestId, req.input))
                }
                is PermissionDecision.Deny -> {
                    deniedToolUses += toolId
                    connection?.send(CliMessages.deny(requestId, decision.message))
                }
                is PermissionDecision.Option -> Unit
            }
            val status = if (decision is PermissionDecision.Deny) ToolStatus.DENIED else ToolStatus.RUNNING
            toolCalls[toolId]?.let { upsertTool(it.copy(status = status, permissionRequestId = null)) }
            if (pendingPermissions.isEmpty() && state == SessionState.AWAITING_PERMISSION) {
                val subagent = toolCalls[toolId]?.parentId != null
                beginRunning(if (decision is PermissionDecision.Deny || subagent) toolPhase() else Activity.Running(req.toolName))
            }
            reportActivity()
        }
    }

    /**
     * Answers a pending AskUserQuestion request. False (and nothing sent) when [requestId] is not a pending question
     * or [answers] don't match its questions and options (see [Questions.answerTexts]).
     */
    override fun answer(requestId: String, answers: Map<String, QuestionAnswer>): Boolean {
        synchronized(lock) {
            val req = pendingPermissions[requestId]?.takeIf { it.toolName == ASK_USER_QUESTION } ?: return false
            val toolId = req.toolUseId ?: "perm-$requestId"
            val card = toolCalls[toolId]?.question ?: return false
            val texts = Questions.answerTexts(card.questions, answers)
            if (texts == null) { answerFailed(requestId); return false }
            pendingPermissions.remove(requestId)
            connection?.send(CliMessages.allow(requestId, Questions.withAnswers(req.input, texts)))
            toolCalls[toolId]?.let { upsertTool(it.copy(status = ToolStatus.RUNNING, permissionRequestId = null, question = card.copy(answers = texts))) }
            if (pendingPermissions.isEmpty() && state == SessionState.AWAITING_PERMISSION) {
                beginRunning(Activity.Thinking)
            }
            reportActivity()
            return true
        }
    }

    /** Tells the user an answer was refused and re-sends the pending question item so the page re-enables its Submit. */
    override fun answerFailed(requestId: String) {
        synchronized(lock) {
            val req = pendingPermissions[requestId]?.takeIf { it.toolName == ASK_USER_QUESTION } ?: return
            emit(ChatItem.Notice(nextId("notice"), "Couldn't send the answer \u2014 please try again or Skip.", isError = true))
            toolCalls[req.toolUseId ?: "perm-$requestId"]?.let { emit(it) }
        }
    }

    /** The questions of the pending AskUserQuestion request [requestId], or null when there is none. */
    override fun pendingQuestions(requestId: String): List<Question>? = synchronized(lock) {
        val req = pendingPermissions[requestId]?.takeIf { it.toolName == ASK_USER_QUESTION } ?: return null
        toolCalls[req.toolUseId ?: "perm-$requestId"]?.question?.questions
    }

    /** Switches Claude's permission mode for a recognized CLI mode id; unknown ids are ignored. */
    override fun setMode(id: String) {
        PermissionMode.fromCli(id)?.let(::setPermissionMode)
    }

    fun setPermissionMode(newMode: PermissionMode) {
        synchronized(lock) {
            mode = newMode
            connection?.send(CliMessages.setPermissionMode(nextId("mode"), newMode.cli))
            notifyState()
        }
    }

    /** Switches to [value] (an [ModelOption.value]) now, and in every process this chat starts from now on. */
    override fun setModel(value: String) = choose({ model }, { model = it }, value) { id -> CliMessages.setModel(id, value) }

    /** Sets the effort level now, and in every process this chat starts from now on. */
    override fun setEffort(level: String) = choose({ effort }, { effort = it }, level) { id -> CliMessages.setEffort(id, level) }

    /**
     * Records [value] and sends the request [message] builds. Once it is answered the settings are asked for again, so
     * the pickers show what runs; a refused value is forgotten (the old one is kept for the next process) unless a newer
     * pick has replaced it meanwhile.
     */
    private fun choose(current: () -> String?, set: (String?) -> Unit, value: String, message: (String) -> String) {
        synchronized(lock) {
            val before = current()
            if (value == before) return
            set(value)
            val conn = connection ?: return
            val id = nextId("choice")
            pendingChoices[id] = { if (current() == value) set(before) }
            conn.send(message(id))
        }
    }

    /** Sends the CLI a stop_task request; the task's own events then report how it ended. */
    override fun stopTask(taskId: String) {
        synchronized(lock) {
            val conn = connection ?: return
            conn.send(CliMessages.stopTask(nextId("stop"), taskId))
        }
    }

    /** Stops the running turn; queued messages are dropped (see [ChatListener.onDropped]). */
    override fun interrupt() {
        synchronized(lock) {
            if (!turnOpen) return
            dropQueue()
            if (!interruptRequested) stopTurn()
        }
    }

    private fun stopTurn() {
        interruptRequested = true
        for ((requestId, req) in pendingPermissions) {
            val toolId = req.toolUseId ?: "perm-$requestId"
            deniedToolUses += toolId
            connection?.send(CliMessages.deny(requestId, "Interrupted by the user."))
            toolCalls[toolId]?.let { upsertTool(it.copy(status = ToolStatus.DENIED, permissionRequestId = null)) }
        }
        if (pendingPermissions.isNotEmpty()) phase = Activity.Thinking
        pendingPermissions.clear()
        connection?.send(CliMessages.interrupt(nextId("interrupt")))
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

    private val turnOpen get() = state == SessionState.RUNNING || state == SessionState.AWAITING_PERMISSION

    private fun notifyQueue() = listener.onQueue(queue.map { it.shown })

    /** Respawns a dead session with --resume, the current permission mode, model and effort, and the pending fork if any. */
    override fun restart() {
        synchronized(lock) {
            if (state != SessionState.DEAD) return
            start()
        }
    }

    /** Whether [itemId] is a message this session sent, so the conversation can be taken back to before it. */
    override fun canRewindTo(itemId: String): Boolean = synchronized(lock) { itemId in anchors }

    /**
     * Takes the conversation back to before message [itemId]: claude restarts resumed at the answer before it, in a
     * fork (the old transcript stays as it was), or as a fresh session when it was the first message. False, changing
     * nothing, while a turn is open, for a message this session didn't send, or when there is no session to resume from.
     */
    override fun rewind(itemId: String): Boolean = synchronized(lock) {
        if (turnOpen || itemId !in anchors) return false
        val at = anchors[itemId]
        val from = sessionId ?: fork?.from
        if (at != null && from == null) return false
        val keys = anchors.keys.toList()
        keys.subList(keys.indexOf(itemId), keys.size).forEach { id ->
            anchors.remove(id)
            imageFiles.drop(id)
        }
        generation++
        connection?.stop()
        forgetProcess()
        lastAnswer = at
        fork = if (at != null) Fork(from!!, at) else null
        sessionId = null
        start()
        reportActivity()
        true
    }

    override fun dispose() {
        synchronized(lock) {
            generation++
            connection?.stop()
            connection = null
        }
        trees?.close()
    }

    override fun diffFor(toolUseId: String): FileDiff? = synchronized(lock) { diffs[toolUseId] }

    override fun imageFile(itemId: String, index: Int): String? = synchronized(lock) {
        imageFiles.get(itemId, index)
    }

    override fun toolCall(id: String): ChatItem.ToolCall? = synchronized(lock) { toolCalls[id] }

    /** The diffs of the files the turn ended by result item [resultId] edited, in its list's order; empty when unknown. */
    override fun turnDiffs(resultId: String): List<FileDiff> = synchronized(lock) { turnDiffs[resultId].orEmpty() }

    /** Display copies for a tool result's images: at most [MAX_TOOL_IMAGES] are converted, the rest show as "[image]". */
    private fun displayCopiesOf(event: CliEvent): List<String>? =
        (event as? CliEvent.ToolResult)?.images?.mapIndexed { i, block -> if (i < MAX_TOOL_IMAGES) displayCopy(block) else "" }

    /**
     * Applies one event from the current process, under the lock. A failed context-usage or settings request is only
     * logged: an older CLI may not know it.
     */
    private fun handle(event: CliEvent, copies: List<String>? = null) {
        when (event) {
            is CliEvent.SystemInit -> {
                if (event.sessionId.isNotBlank()) { sessionId = event.sessionId; fork = null }
                PermissionMode.fromCli(event.permissionMode)?.let { mode = it }
                event.cwd?.takeIf { it.isNotBlank() }?.let { cwd = it; cwdReported = true }
                notifyState()
            }
            is CliEvent.ControlResponse -> {
                if (event.requestId.startsWith(CONTEXT_PREFIX)) {
                    if (event.requestId != contextRequestId) return
                    contextRequestId = null
                    if (event.isSuccess) event.response?.let(ContextUsage::parse)?.let(listener::onContext)
                    else log("Context usage request failed: ${event.error}")
                    return
                }
                if (event.requestId.startsWith(SETTINGS_PREFIX)) {
                    if (event.requestId != settingsRequestId) return
                    settingsRequestId = null
                    if (event.isSuccess) event.response?.let(Models::running)?.let(::reportModel)
                    else log("Settings request failed: ${event.error}")
                    return
                }
                if (event.requestId == initRequestId) {
                    initRequestId = null
                    if (event.isSuccess) {
                        modelOptions = event.response?.let(Models::options).orEmpty()
                        event.response?.let(SlashCommands::fromInitialize)?.let(listener::onCommands)
                        requestContextUsage()
                        requestSettings()
                    }
                }
                pendingChoices.remove(event.requestId)?.let { undo ->
                    if (!event.isSuccess) undo()
                    requestSettings()
                }
                if (!event.isSuccess) {
                    emit(ChatItem.Notice(nextId("notice"), "Claude rejected a request: ${event.error ?: "unknown error"}", isError = true))
                } else {
                    val reported = event.response?.let { it.str("current_permission_mode") ?: it.str("mode") }
                    PermissionMode.fromCli(reported)?.let { mode = it; notifyState() }
                }
            }
            is CliEvent.TextDelta -> {
                val key = "${event.messageId}:${event.index}"
                streamedMessages += event.messageId
                val current = streamed[key] ?: ChatItem.AssistantText("text-$key", "", true)
                val next = current.copy(text = current.text + event.text, streaming = true)
                streamed[key] = next
                emit(next)
                phase = Activity.Writing
                reportActivity()
            }
            is CliEvent.MessageStart -> onMessageStart()
            is CliEvent.BackgroundTasks -> {
                backgroundTasks = event.tasks
                reportProcesses()
                reportActivity()
            }
            is CliEvent.ThinkingStart -> { phase = Activity.Thinking; reportActivity() }
            is CliEvent.BlockStop -> streamed.remove("${event.messageId}:${event.index}")?.let { emit(it.copy(streaming = false)) }
            is CliEvent.AssistantText -> onAssistantText(event)
            is CliEvent.AssistantMessage -> lastAnswer = event.uuid
            is CliEvent.ToolUse -> onToolUse(event)
            is CliEvent.TaskStarted ->
                if (event.taskType != null && event.taskType != AGENT_TASK) otherTasks += event.toolUseId
                else updateAgent(event.toolUseId) {
                    it.copy(subagentType = event.subagentType, background = event.background, prompt = capped(event.prompt), taskId = event.taskId)
                }
            is CliEvent.TaskProgress -> updateAgent(event.toolUseId) {
                it.copy(toolUses = event.toolUses ?: it.toolUses, durationMs = event.durationMs ?: it.durationMs, lastTool = event.lastTool)
            }
            is CliEvent.TaskDone -> if (otherTasks.remove(event.toolUseId)) Unit else updateAgent(event.toolUseId) {
                it.copy(
                    state = if (event.status == "completed") AgentState.DONE else AgentState.FAILED,
                    toolUses = event.toolUses ?: it.toolUses, durationMs = event.durationMs ?: it.durationMs,
                    lastTool = null, report = capped(event.report) ?: it.report,
                )
            }
            is CliEvent.PermissionRequest -> onPermissionRequest(event)
            is CliEvent.McpMessage -> onMcpMessage(event)
            is CliEvent.HookCallback -> onHookCallback(event)
            is CliEvent.ToolResult -> onToolResult(event, copies.orEmpty())
            is CliEvent.UserText -> Unit
            is CliEvent.Result -> onResult(event)
            is CliEvent.PromptSuggestion -> onSuggestion(event.text)
            is CliEvent.Ignored -> Unit
            is CliEvent.Malformed -> log("Malformed line from claude: ${event.line.take(200)}")
        }
    }

    /** A message starting while idle is a turn the CLI began by itself, e.g. to read a finished background agent's report. */
    private fun onMessageStart() {
        if (state == SessionState.IDLE) {
            beginRunning(Activity.Thinking)
            reportActivity()
        }
    }

    /** A subagent's messages arrive whole; the main conversation's are streamed, so one is shown here only when no deltas came for it. */
    private fun onAssistantText(event: CliEvent.AssistantText) {
        when {
            event.parentId != null -> emit(ChatItem.AssistantText(nextId("text"), event.text, false, event.parentId))
            event.messageId !in streamedMessages -> emit(ChatItem.AssistantText(nextId("text"), event.text, false))
        }
    }

    /** Shows the tool call; the activity strip follows only the main conversation's tools, a subagent's show in its card. */
    private fun onToolUse(event: CliEvent.ToolUse) {
        if (event.parentId != null && event.name == HANDBACK) return
        val base = toolCalls[event.toolUseId]
            ?: ChatItem.ToolCall(event.toolUseId, event.name, event.input, null, ToolStatus.RUNNING, null, null, false)
        upsertTool(base.copy(
            name = event.name, input = event.input, filePath = filePathOf(event.input),
            parentId = event.parentId ?: base.parentId, agent = agents[event.toolUseId],
        ))
        if (event.parentId == null) {
            runningTools[event.toolUseId] = event.name
            phase = Activity.Running(event.name)
            reportActivity()
        }
    }

    private fun onPermissionRequest(event: CliEvent.PermissionRequest) {
        val isQuestion = event.toolName == ASK_USER_QUESTION
        val questions = if (isQuestion) Questions.parse(event.input) else null
        if (isQuestion && questions == null) {
            connection?.send(CliMessages.deny(event.requestId, "The question was malformed."))
            deniedToolUses += event.toolUseId ?: "perm-${event.requestId}"
            emit(ChatItem.Notice(nextId("notice"), "Claude asked a question that could not be shown.", isError = true))
            return
        }
        if (!isQuestion && (event.toolName in sessionAllowedTools || event.toolName in readOnlyTools)) {
            connection?.send(CliMessages.allow(event.requestId, event.input))
            return
        }
        pendingPermissions[event.requestId] = event
        val id = event.toolUseId ?: "perm-${event.requestId}"
        val base = toolCalls[id]
            ?: ChatItem.ToolCall(id, event.toolName, event.input, filePathOf(event.input), ToolStatus.RUNNING, null, null, false)
        upsertTool(base.copy(
            status = ToolStatus.AWAITING_PERMISSION,
            permissionRequestId = event.requestId,
            question = questions?.let { QuestionCard(it) },
        ))
        setState(SessionState.AWAITING_PERMISSION)
        reportActivity()
    }

    /** Answers Claude's MCP traffic to [IDE_SERVER]; tool calls run in [background] and answer the process that asked. */
    private fun onMcpMessage(event: CliEvent.McpMessage) {
        val conn = connection ?: return
        val server = mcpServer?.takeIf { event.server == IDE_SERVER }
        when {
            server == null -> conn.send(CliMessages.controlError(event.requestId, "No MCP server named ${event.server}"))
            !server.isRequest(event.message) -> conn.send(CliMessages.mcpResponse(event.requestId, McpServer.ACK))
            else -> background { conn.send(CliMessages.mcpResponse(event.requestId, server.reply(event.message))) }
        }
    }

    /** Answers a hook callback; the CLI waits for every callback, so one is always sent. */
    private fun onHookCallback(event: CliEvent.HookCallback) {
        val work = when (event.callbackId) {
            BEFORE_BASH -> beforeBash(event)
            AFTER_BASH -> afterBash(event)
            AFTER_EDIT -> afterEdit(event)
            else -> null
        }
        answerHook(event, work)
    }

    /**
     * Answers [event] with what [work] returns (context for Claude, or null), run in [background]; at once with nothing
     * when there is no work. A failure is logged and still answered.
     */
    private fun answerHook(event: CliEvent.HookCallback, work: (() -> String?)?) {
        val conn = connection ?: return
        val name = event.input.str("hook_event_name") ?: POST_TOOL_USE
        if (work == null) {
            conn.send(CliMessages.hookResponse(event.requestId, name, null))
            return
        }
        background {
            var note: String? = null
            try {
                note = work()
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                log("$name hook ${event.callbackId} failed: $e")
            } finally {
                conn.send(CliMessages.hookResponse(event.requestId, name, note))
            }
        }
    }

    /** Snapshots the checkout before a Bash call runs; the call waits for it. */
    private fun beforeBash(event: CliEvent.HookCallback): (() -> String?)? {
        val t = trees ?: return null
        val id = event.input.str("tool_use_id") ?: return null
        val dir = event.input.str("cwd") ?: cwd
        val gen = generation
        return {
            t.snapshot(dir)?.let { snap -> synchronized(lock) { if (gen == generation) snapshotsBeforeBash[id] = snap } }
            null
        }
    }

    /** Adds the files a Bash call changed to the turn's edits, each with its content from before the call. */
    private fun afterBash(event: CliEvent.HookCallback): (() -> String?)? {
        val t = trees ?: return null
        val before = event.input.str("tool_use_id")?.let(snapshotsBeforeBash::remove) ?: return null
        val gen = generation
        return {
            val known = synchronized(lock) { turnEdits.paths() }
            val changed = t.snapshot(before.root)?.let { t.changes(before, it, known) }
            if (!changed.isNullOrEmpty()) synchronized(lock) {
                if (gen == generation) changed.forEach { (path, original) -> turnEdits.record(path, original) }
            }
            null
        }
    }

    /** Runs [IdeHost.afterEdit] for the file an edit tool wrote; its note goes back to Claude. */
    private fun afterEdit(event: CliEvent.HookCallback): (() -> String?)? {
        val host = ide ?: return null
        val path = event.input.obj("tool_input")?.let { it.str("file_path") ?: it.str("notebook_path") } ?: return null
        return { host.afterEdit(path) }
    }

    private fun onToolResult(event: CliEvent.ToolResult, copies: List<String>) {
        if (event.parentId == null) {
            runningTools.remove(event.toolUseId)
            phase = toolPhase()
            reportActivity()
        }
        val tool = toolCalls[event.toolUseId] ?: return
        val status = when {
            event.toolUseId in deniedToolUses -> ToolStatus.DENIED
            event.isError -> ToolStatus.ERROR
            else -> ToolStatus.DONE
        }
        val editedPath = (event.filePath ?: tool.filePath)?.takeIf { event.hasOriginalFile && !event.isError }
        val original = event.originalFile
        if (editedPath != null) {
            if (original != null) diffs[tool.id] = FileDiff(editedPath, original)
            turnEdits.record(editedPath, original)
        }
        val hasDiff = editedPath != null && original != null
        upsertTool(tool.copy(
            status = status,
            permissionRequestId = null,
            resultPreview = event.content.take(4000),
            hasDiff = hasDiff,
            images = copies,
        ))
    }

    /**
     * A display copy (at most 800 px and 512 KB) of an image the CLI returned, as a `data:` URL; "" when the block
     * is not a usable image. The CLI's claimed media type and its raw base64 never reach the page: the bytes are
     * decoded, sniffed and re-encoded by [Images.preview], and the URL is built from the re-encoded type.
     * Runs on the CLI reader thread, outside the session lock.
     */
    private fun displayCopy(block: ImageBlock): String {
        if (block.data.length > MAX_TOOL_IMAGE_BASE64) return ""
        val bytes = try { Base64.getDecoder().decode(block.data) } catch (e: IllegalArgumentException) { return "" }
        if (bytes.size > Images.MAX_INPUT_BYTES) return ""
        return Images.preview(bytes)?.dataUrl().orEmpty()
    }

    /**
     * Ends the turn: a foreground agent ends within its turn, so one still running now was cut off (e.g. interrupted);
     * then the result (or "Interrupted") is shown and the next queued message, if any, goes out.
     */
    private fun onResult(event: CliEvent.Result) {
        endAgents { !it.background }
        pendingPermissions.clear()
        runningTools.clear()
        phase = null
        if (interruptRequested) {
            interruptRequested = false
            turnEdits.clear()
            emit(ChatItem.Notice(nextId("notice"), "Interrupted", isError = false))
        } else {
            val text = if (event.isError) event.text ?: event.errors.joinToString("\n") else null
            val result = ChatItem.TurnResult(nextId("result"), event.isError, text, event.durationMs, event.costUsd)
            emit(result)
            addEditedFiles(result, turnEdits.take())
        }
        requestContextUsage()
        val next = queue.removeFirstOrNull()
        if (next != null) {
            notifyQueue()
            val item = ChatItem.UserText(nextId("user"), next.shown.text, next.shown.images)
            imageFiles.keep(item.id, next.files)
            anchors[item.id] = lastAnswer
            emit(item)
            connection?.send(next.line)
            beginRunning(Activity.Starting)
        } else {
            setState(SessionState.IDLE)
        }
        reportActivity()
    }

    /** Re-sends [result] with the files the turn edited; the disk reads and diffs run in [background], off the lock. */
    private fun addEditedFiles(result: ChatItem.TurnResult, originals: Map<String, String?>) {
        if (originals.isEmpty()) return
        val gen = generation
        background {
            val edited = TurnEdits.summarize(originals)
            if (edited.isNotEmpty()) synchronized(lock) {
                if (gen != generation) return@synchronized
                turnDiffs[result.id] = edited.map { it.second }
                emit(result.copy(files = edited.map { it.first }))
            }
        }
    }

    /** A suggestion that arrives once the next turn has begun is stale, so it is dropped. */
    private fun onSuggestion(raw: String) {
        val text = raw.trim()
        if (state != SessionState.IDLE || text.isEmpty() || text.length > MAX_SUGGESTION_CHARS) return
        listener.onSuggestion(text)
    }

    /**
     * Drops everything that belonged to the claude process that is gone or being replaced; text it was still streaming
     * is re-emitted as finished.
     */
    private fun forgetProcess() {
        connection = null
        interruptRequested = false
        streamed.values.forEach { emit(it.copy(streaming = false)) }
        streamed.clear()
        otherTasks.clear()
        mcpServer = null
        readOnlyTools = emptySet()
        initRequestId = null
        contextRequestId = null
        settingsRequestId = null
        modelOptions = emptyList()
        pendingChoices.clear()
        pendingPermissions.clear()
        runningTools.clear()
        turnEdits.clear()
        snapshotsBeforeBash.clear()
        backgroundTasks = emptyList()
        reportProcesses()
        endAgents { true }
        phase = null
    }

    private fun handleExit(code: Int, stderrTail: String) {
        forgetProcess()
        dropQueue()
        emit(ChatItem.Notice(nextId("notice"), CliConnection.exitNotice("Claude", code, stderrTail), isError = true))
        setState(SessionState.DEAD)
        reportActivity()
    }

    private fun updateAgent(toolUseId: String, change: (AgentInfo) -> AgentInfo) {
        if (toolUseId in otherTasks) return
        val old = agents[toolUseId] ?: AgentInfo(null, false, null, AgentState.RUNNING)
        val info = change(old)
        if (info == old && toolUseId in agents) return
        agents[toolUseId] = info
        toolCalls[toolUseId]?.let { upsertTool(it.copy(agent = info)) }
    }

    /** Marks running agents that [cutOff] picks as failed: no task_notification will come for them. */
    private fun endAgents(cutOff: (AgentInfo) -> Boolean) {
        for ((id, info) in agents.entries.toList()) {
            if (info.state == AgentState.RUNNING && cutOff(info)) updateAgent(id) { it.copy(state = AgentState.FAILED, lastTool = null) }
        }
    }

    /** The phase while the main conversation's tools run: the newest running tool, or thinking when none is. */
    private fun toolPhase(): Activity = runningTools.entries.lastOrNull()?.let { Activity.Running(it.value) } ?: Activity.Thinking

    private fun requestContextUsage() {
        val conn = connection ?: return
        val id = nextId(CONTEXT_PREFIX)
        contextRequestId = id
        conn.send(CliMessages.getContextUsage(id))
    }

    /** Asks what runs; not while the process offers no models, since there is then nothing to show. */
    private fun requestSettings() {
        if (modelOptions.isEmpty()) return
        val conn = connection ?: return
        val id = nextId(SETTINGS_PREFIX)
        settingsRequestId = id
        conn.send(CliMessages.getSettings(id))
    }

    private fun reportModel(running: RunningModel) {
        listener.onModel(ModelChoice.of(modelOptions, model, running))
    }

    private fun filePathOf(input: JsonObject): String? = input.str("file_path") ?: input.str("notebook_path")
    private fun nextId(prefix: String) = "$prefix-${++seq}"
    private fun upsertTool(tool: ChatItem.ToolCall) { toolCalls[tool.id] = tool; emit(tool) }
    private fun emit(item: ChatItem) = listener.onItem(item)
    private fun setState(newState: SessionState) { state = newState; notifyState() }
    /** Enters RUNNING with [phase] set first: a running session always has an activity (Chat.busy relies on it). */
    private fun beginRunning(phase: Activity) { this.phase = phase; setState(SessionState.RUNNING) }
    /**
     * Tells the listener the background tasks that aren't subagents, if they changed. A task keeps the start time it was
     * first seen with, so its clock doesn't restart when another task comes or goes.
     */
    private fun reportProcesses() {
        val known = processes.associateBy { it.id }
        val now = backgroundTasks.filter { it.type != AGENT_TASK && !it.ambient }.map { task ->
            BackgroundProcess(task.taskId, processKind(task.type), task.description, known[task.taskId]?.startedAt ?: clock())
        }
        if (now == processes) return
        processes = now
        listener.onProcesses(now)
    }

    /** Tells the listener the current activity if it differs from the last one told. */
    private fun reportActivity() {
        val now = if (turnOpen) {
            if (pendingPermissions.isNotEmpty()) Activity.Waiting else phase
        } else if (state == SessionState.IDLE && backgroundTasks.isNotEmpty()) Activity.Agents(backgroundTasks.size) else null
        if (now == reported) return
        reported = now
        listener.onActivity(now)
    }
    private fun notifyState() = listener.onState(state, sessionId, mode)
}
