package tether.session

import tether.image.Image

/** One chat's conversation with its agent: [ChatSession] for Claude, `AcpSession` for an ACP agent. Thread-safe. */
interface AgentSession {
    val state: SessionState
    val sessionId: String?
    /** The agent's current mode. Before an ACP session opens it is the mode the session will ask for once it does. */
    val modeId: String?
    /** The folder the agent works in, as far as known now. */
    val cwd: String

    /** Whether [cwd] is authoritative rather than the launch folder of a pending native worktree. */
    val cwdReported: Boolean

    /** Native launch choices; empty for agents whose setup is controlled by their protocol. */
    val setup: SessionSetup

    fun start()

    /**
     * Shows [text] (with [thumbs] of its images above it) in the chat; sends "[prefix]\n\n[text]" and [images] to the
     * agent. [files] are the images' paths on disk ("" for one that has none), kept for [imageFile]. While a turn is
     * running the message is queued instead (see [ChatListener.onQueue]) and enters the chat when it goes out.
     */
    fun send(
        text: String,
        prefix: String? = null,
        images: List<Image> = emptyList(),
        thumbs: List<String> = emptyList(),
        files: List<String> = emptyList(),
    )

    /** The path on disk of image [index] of user message [itemId], or null when it has none. */
    fun imageFile(itemId: String, index: Int): String?

    /** Tool call [id] as last shown, or null when this session has none by that id. */
    fun toolCall(id: String): ChatItem.ToolCall?

    /** Takes queued message [id] out of the queue without sending it; null when it is not queued (any more). */
    fun unqueue(id: String): QueuedMessage?

    /** Stops the running turn and sends queued message [id] next; the rest of the queue follows it. */
    fun sendNow(id: String)

    /** Shows a notice in the chat (e.g. an image that could not be attached). Any thread. */
    fun notice(text: String, isError: Boolean = true)

    fun respond(requestId: String, decision: PermissionDecision)

    /** Ignored when the agent offers no mode [id]. */
    fun setMode(id: String)

    /** Changes the native model when supported; ignored by agents without model selection. */
    fun setModel(value: String) {}

    /** Changes the native effort when supported; ignored by agents without effort selection. */
    fun setEffort(level: String) {}

    /** Stops the running turn; queued messages are dropped (see [ChatListener.onDropped]). */
    fun interrupt()

    /**
     * Turns Remote Control on or off (see [RemoteControl]); the outcome comes to [ChatListener.onRemoteControl]. Ignored
     * by agents without it, while no process runs, and while an earlier change is still unanswered.
     */
    fun setRemoteControl(on: Boolean) {}

    /** Stops one subagent or background process by its task id, leaving the turn running; ignored by agents without tasks. */
    fun stopTask(taskId: String) {}

    /** Starts a dead session again, continuing the same conversation where the agent can. */
    fun restart()

    fun dispose()

    fun diffFor(toolUseId: String): FileDiff?

    /** Whether the agent can take the conversation back to before [itemId]. */
    fun canRewindTo(itemId: String): Boolean = false

    /** Rewinds the conversation when supported and currently possible; false changes nothing. */
    fun rewind(itemId: String): Boolean = false

    /** The files changed in a native turn, or empty when the agent does not report them. */
    fun turnDiffs(resultId: String): List<FileDiff> = emptyList()

    /** Native structured questions for [requestId]; null when unsupported or unknown. */
    fun pendingQuestions(requestId: String): List<Question>? = null

    /** Answers native structured questions; false when unsupported or invalid. */
    fun answer(requestId: String, answers: Map<String, QuestionAnswer>): Boolean = false

    /** Re-enables a pending native question after a refused answer; ignored when unsupported. */
    fun answerFailed(requestId: String) {}
}
