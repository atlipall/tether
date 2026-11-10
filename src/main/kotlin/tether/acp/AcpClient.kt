package tether.acp

import tether.cli.SlashCommand
import tether.image.Image
import tether.session.Choice
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connection to one Agent Client Protocol agent process. Calls never block. Listener calls and future completions
 * arrive on one thread in wire order: a turn's last updates arrive before its prompt completes. When the transport
 * ends, open futures fail with [ConnectionLost] first, then [AcpListener.onClosed] runs once; later calls fail at once.
 */
interface AcpClient {
    /** Starts the agent and sends `initialize`. */
    fun connect(listener: AcpListener): CompletableFuture<AgentInfo>
    fun newSession(cwd: String): CompletableFuture<SessionOpened>
    /** Updates that replay the history arrive before the future completes. */
    fun loadSession(sessionId: String, cwd: String): CompletableFuture<SessionOpened>
    /** Completes with the ACP stop reason, e.g. `end_turn` or `cancelled`. */
    fun prompt(sessionId: String, text: String, images: List<Image>): CompletableFuture<String>
    fun setMode(sessionId: String, modeId: String): CompletableFuture<Unit>
    fun cancel(sessionId: String)
    /** Ends the transport; sends no cancel. */
    fun close()
}

interface AcpListener {
    fun onUpdate(sessionId: String, update: AcpUpdate)
    fun onPermission(request: PermissionRequest)
    fun onClosed(exitCode: Int?, detail: String)
}

data class AgentInfo(val loadSession: Boolean, val images: Boolean)

data class SessionOpened(val sessionId: String, val modes: List<Choice>, val currentMode: String?)

sealed interface AcpUpdate {
    data class UserText(val messageId: String?, val text: String) : AcpUpdate
    data class AgentText(val messageId: String?, val text: String) : AcpUpdate
    data object Thought : AcpUpdate
    /** A tool call or an update of one; null fields are unchanged. [status] is ACP's: pending, in_progress, completed, failed. */
    data class Tool(
        val id: String,
        val title: String?,
        val status: String?,
        val input: JsonObject?,
        val path: String?,
        val text: String?,
    ) : AcpUpdate
    data class Mode(val id: String) : AcpUpdate
    data class Usage(val used: Long, val size: Long) : AcpUpdate
    /** The commands the agent now offers, replacing any it offered before. */
    data class Commands(val commands: List<SlashCommand>) : AcpUpdate
    data class Other(val kind: String) : AcpUpdate
}

data class PermissionOption(val id: String, val name: String, val kind: String)

/** The agent asks before a tool call. Answered at most once; answers after the connection ended do nothing. */
class PermissionRequest(
    val sessionId: String,
    val tool: AcpUpdate.Tool,
    val options: List<PermissionOption>,
    private val reply: (optionId: String?) -> Unit,
) {
    private val answered = AtomicBoolean()

    fun select(optionId: String) { if (answered.compareAndSet(false, true)) reply(optionId) }

    fun cancel() { if (answered.compareAndSet(false, true)) reply(null) }
}

class AgentError(val code: Int, message: String) : Exception(message)

/** The transport ended before an answer came; for a prompt it is unknown whether the agent accepted it. */
class ConnectionLost(message: String) : Exception(message)
