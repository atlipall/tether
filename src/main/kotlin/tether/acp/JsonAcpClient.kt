package tether.acp

import tether.cli.CliConnection
import tether.cli.arr
import tether.cli.bool
import tether.cli.long
import tether.cli.obj
import tether.cli.parseJsonObject
import tether.cli.SlashCommands
import tether.cli.str
import tether.image.Image
import tether.session.Choice
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * [AcpClient] as JSON-RPC 2.0 over one [CliConnection], one message per line. The connection starts on [launch];
 * events arrive on its reader thread.
 */
class JsonAcpClient(
    private val connection: CliConnection,
    /** Runs the start of the agent process, so that [connect] never blocks its caller. */
    private val launch: (Runnable) -> Unit = { thread(isDaemon = true, name = "acp-start", block = it::run) },
) : AcpClient {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    @Volatile private var closed = false
    private val ended = AtomicBoolean()
    @Volatile private var listener: AcpListener? = null
    /** Guards [started] and [closed] together, so that a close while the process starts still stops it. */
    private val lifecycle = Any()
    private var started = false

    override fun connect(listener: AcpListener): CompletableFuture<AgentInfo> {
        this.listener = listener
        val info = CompletableFuture<AgentInfo>()
        launch(Runnable {
            try {
                startTransport()
            } catch (e: Exception) {
                closed = true
                info.completeExceptionally(e)
                return@Runnable
            }
            initialize().whenComplete { result, error -> if (error == null) info.complete(result) else info.completeExceptionally(error) }
        })
        return info
    }

    private fun startTransport() {
        synchronized(lifecycle) { if (closed) throw lost() }
        connection.start(::receive, ::end)
        if (synchronized(lifecycle) { started = true; closed }) connection.stop()
    }

    private fun initialize(): CompletableFuture<AgentInfo> =
        request("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("clientCapabilities") {}
        }).thenApply { result ->
            val version = result.long("protocolVersion")
            check(version == PROTOCOL_VERSION) { "The agent speaks ACP protocol version $version; Tether speaks $PROTOCOL_VERSION." }
            val capabilities = result.obj("agentCapabilities")
            AgentInfo(
                loadSession = capabilities?.bool("loadSession") == true,
                images = capabilities?.obj("promptCapabilities")?.bool("image") == true,
            )
        }

    override fun newSession(cwd: String): CompletableFuture<SessionOpened> =
        request("session/new", sessionParams(null, cwd)).thenApply { result ->
            opened(checkNotNull(result.str("sessionId")) { "The agent opened a session without an id." }, result)
        }

    override fun loadSession(sessionId: String, cwd: String): CompletableFuture<SessionOpened> =
        request("session/load", sessionParams(sessionId, cwd)).thenApply { opened(sessionId, it) }

    override fun prompt(sessionId: String, text: String, images: List<Image>): CompletableFuture<String> =
        request("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("prompt") {
                addJsonObject { put("type", "text"); put("text", text) }
                for (image in images) addJsonObject {
                    put("type", "image")
                    put("mimeType", image.type.mime)
                    put("data", image.base64())
                }
            }
        }).thenApply { it.str("stopReason").orEmpty() }

    override fun setMode(sessionId: String, modeId: String): CompletableFuture<Unit> =
        request("session/set_mode", buildJsonObject { put("sessionId", sessionId); put("modeId", modeId) }).thenApply {}

    override fun cancel(sessionId: String) {
        if (!closed) connection.send(envelope { put("method", "session/cancel"); putJsonObject("params") { put("sessionId", sessionId) } })
    }

    override fun close() {
        if (synchronized(lifecycle) { closed = true; started }) connection.stop()
    }

    private fun request(method: String, params: JsonObject): CompletableFuture<JsonObject> {
        val future = CompletableFuture<JsonObject>()
        if (closed) return future.also { it.completeExceptionally(lost()) }
        val id = ids.incrementAndGet()
        pending[id] = future
        if (ended.get()) pending.remove(id)?.completeExceptionally(lost())
        else connection.send(envelope { put("id", id); put("method", method); put("params", params) })
        return future
    }

    private fun receive(line: String) {
        val msg = parseJsonObject(line) ?: return LOG.info("Not JSON from the ACP agent: ${line.take(200)}")
        val id = msg["id"]?.takeUnless { it is JsonNull }
        val method = msg.str("method")
        when {
            method == null -> (id as? JsonPrimitive)?.longOrNull?.let(pending::remove)?.let { future ->
                val error = msg.obj("error")
                if (error == null) future.complete(msg.obj("result") ?: JsonObject(emptyMap()))
                else future.completeExceptionally(AgentError(error.long("code")?.toInt() ?: 0, error.str("message") ?: "Unknown error"))
            }
            id == null -> if (method == "session/update") update(msg.obj("params"))
            method == "session/request_permission" -> permission(id, msg.obj("params"))
            else -> answerError(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun end(exitCode: Int, detail: String) {
        if (!ended.compareAndSet(false, true)) return
        closed = true
        for (id in pending.keys.toList()) pending.remove(id)?.completeExceptionally(lost())
        listener?.onClosed(exitCode, detail)
    }

    private fun update(params: JsonObject?) {
        val sessionId = params?.str("sessionId") ?: return
        val update = params.obj("update") ?: return
        listener?.onUpdate(sessionId, parseUpdate(update))
    }

    private fun permission(id: JsonElement, params: JsonObject?) {
        val sessionId = params?.str("sessionId")
        val tool = params?.obj("toolCall")?.let(::tool)
        val target = listener
        if (sessionId == null || tool == null || target == null) return answerError(id, INVALID_PARAMS, "Invalid permission request")
        val options = params.arr("options").orEmpty().filterIsInstance<JsonObject>().mapNotNull { o ->
            o.str("optionId")?.let { PermissionOption(it, o.str("name") ?: it, o.str("kind").orEmpty()) }
        }
        target.onPermission(PermissionRequest(sessionId, tool, options) { optionId ->
            connection.send(envelope {
                put("id", id)
                putJsonObject("result") {
                    putJsonObject("outcome") {
                        if (optionId == null) put("outcome", "cancelled")
                        else { put("outcome", "selected"); put("optionId", optionId) }
                    }
                }
            })
        })
    }

    private fun answerError(id: JsonElement, code: Int, message: String) =
        connection.send(envelope { put("id", id); putJsonObject("error") { put("code", code); put("message", message) } })

    private fun lost() = ConnectionLost("The agent's connection ended.")

    companion object {
        private val LOG = logger<JsonAcpClient>()
        const val PROTOCOL_VERSION = 1L
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602

        private fun envelope(body: JsonObjectBuilder.() -> Unit): String = buildJsonObject { put("jsonrpc", "2.0"); body() }.toString()

        private fun sessionParams(sessionId: String?, cwd: String) = buildJsonObject {
            sessionId?.let { put("sessionId", it) }
            put("cwd", cwd)
            putJsonArray("mcpServers") {}
        }

        private fun opened(sessionId: String, result: JsonObject): SessionOpened {
            val modes = result.obj("modes")
            val available = modes?.arr("availableModes").orEmpty().filterIsInstance<JsonObject>().mapNotNull { m ->
                m.str("id")?.let { Choice(it, m.str("name") ?: it) }
            }
            return SessionOpened(sessionId, available, modes?.str("currentModeId"))
        }

        private fun parseUpdate(u: JsonObject): AcpUpdate {
            val kind = u.str("sessionUpdate").orEmpty()
            return when (kind) {
                "user_message_chunk" -> u.obj("content")?.takeIf { it.str("type") == "text" }?.str("text")?.let { AcpUpdate.UserText(u.str("messageId"), it) }
                "agent_message_chunk" -> u.obj("content")?.takeIf { it.str("type") == "text" }?.str("text")?.let { AcpUpdate.AgentText(u.str("messageId"), it) }
                "agent_thought_chunk" -> AcpUpdate.Thought
                "tool_call", "tool_call_update" -> tool(u)
                "current_mode_update" -> u.str("currentModeId")?.let(AcpUpdate::Mode)
                "available_commands_update" -> AcpUpdate.Commands(SlashCommands.fromAcp(u))
                "usage_update" -> u.long("used")?.let { used -> u.long("size")?.let { AcpUpdate.Usage(used, it) } }
                else -> null
            } ?: AcpUpdate.Other(kind)
        }

        private fun tool(o: JsonObject): AcpUpdate.Tool? {
            val id = o.str("toolCallId")?.takeIf { it.isNotEmpty() } ?: return null
            val content = o.arr("content")?.filterIsInstance<JsonObject>().orEmpty()
            val texts = content.mapNotNull { c -> c.obj("content")?.takeIf { it.str("type") == "text" }?.str("text") }
            return AcpUpdate.Tool(
                id = id,
                title = o.str("title"),
                status = o.str("status"),
                input = o.obj("rawInput"),
                path = o.arr("locations")?.filterIsInstance<JsonObject>()?.firstNotNullOfOrNull { it.str("path") }
                    ?: content.firstNotNullOfOrNull { it.str("path") },
                text = texts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            )
        }
    }
}
