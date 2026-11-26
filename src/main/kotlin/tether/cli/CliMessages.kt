package tether.cli

import tether.image.Image
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A hook callback for tools whose name matches [matcher] (a regex such as "Edit|Write"). */
data class HookRegistration(val matcher: String, val callbackId: String, val timeoutSeconds: Int)

/** Builders for lines written to the CLI's stdin. */
object CliMessages {
    /**
     * Opens the control channel. [sdkServers] names in-process MCP servers (their traffic comes back as
     * [CliEvent.McpMessage]); [hooks] maps a hook event (e.g. "PostToolUse") to callback registrations, each a tool
     * matcher and the callback id that [CliEvent.HookCallback] will carry.
     */
    fun initialize(requestId: String, sdkServers: List<String> = emptyList(), hooks: Map<String, List<HookRegistration>> = emptyMap()): String =
        controlRequest(requestId) {
            put("subtype", "initialize")
            if (hooks.isEmpty()) put("hooks", JsonNull)
            else putJsonObject("hooks") {
                for ((event, registrations) in hooks) putJsonArray(event) {
                    for (r in registrations) addJsonObject {
                        put("matcher", r.matcher)
                        putJsonArray("hookCallbackIds") { add(r.callbackId) }
                        put("timeout", r.timeoutSeconds)
                    }
                }
            }
            if (sdkServers.isNotEmpty()) putJsonArray("sdkMcpServers") { sdkServers.forEach { add(it) } }
        }

    /** The JSON-RPC [reply] to an [CliEvent.McpMessage]. */
    fun mcpResponse(requestId: String, reply: JsonObject): String = controlResponse(requestId) { put("mcp_response", reply) }

    /** Answers a [CliEvent.HookCallback]: [additionalContext] is shown to Claude after the tool's result; null says nothing. */
    fun hookResponse(requestId: String, event: String, additionalContext: String?): String = controlResponse(requestId) {
        if (additionalContext != null) putJsonObject("hookSpecificOutput") {
            put("hookEventName", event)
            put("additionalContext", additionalContext)
        }
    }

    /**
     * A user message. [uuid] names it: the CLI keeps it when it replays the message (`--replay-user-messages`), so a
     * replay can be told apart from a prompt that reached the session another way, such as over Remote Control.
     */
    fun userText(text: String, uuid: String? = null): String = buildJsonObject {
        put("type", "user")
        uuid?.let { put("uuid", it) }
        putJsonObject("message") {
            put("role", "user")
            put("content", text)
        }
    }.toString()

    /**
     * A user message with images: a content array of the text block (left out when [text] is blank) and one base64
     * image block per image, in that order. Without images it is [userText]. [uuid] names it, as for [userText].
     */
    fun userContent(text: String, images: List<Image>, uuid: String? = null): String {
        if (images.isEmpty()) return userText(text, uuid)
        return buildJsonObject {
            put("type", "user")
            uuid?.let { put("uuid", it) }
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    if (text.isNotBlank()) addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                    for (image in images) addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", image.type.mime)
                            put("data", image.base64())
                        }
                    }
                }
            }
        }.toString()
    }

    fun allow(requestId: String, input: JsonObject): String = controlResponse(requestId) {
        put("behavior", "allow")
        put("updatedInput", input)
    }

    fun deny(requestId: String, message: String): String = controlResponse(requestId) {
        put("behavior", "deny")
        put("message", message)
    }

    fun interrupt(requestId: String): String = controlRequest(requestId) { put("subtype", "interrupt") }

    /** Stops background or foreground task [taskId] (a subagent, shell command, monitor or workflow); the turn goes on. */
    fun stopTask(requestId: String, taskId: String): String = controlRequest(requestId) {
        put("subtype", "stop_task")
        put("task_id", taskId)
    }

    /** Asks how full the context window is; answered without calling the model. */
    fun getContextUsage(requestId: String): String = controlRequest(requestId) { put("subtype", "get_context_usage") }

    fun setPermissionMode(requestId: String, mode: String): String = controlRequest(requestId) {
        put("subtype", "set_permission_mode")
        put("mode", mode)
    }

    /** Asks for the settings in force; its `applied` block holds the model and effort the session runs with. */
    fun getSettings(requestId: String): String = controlRequest(requestId) { put("subtype", "get_settings") }

    /** Switches the running session to [model] (a value from the initialize reply's model list). */
    fun setModel(requestId: String, model: String): String = controlRequest(requestId) {
        put("subtype", "set_model")
        put("model", model)
    }

    /** Sets the running session's effort level; the CLI has no request of its own for it, so it goes as a flag setting. */
    fun setEffort(requestId: String, effort: String): String = controlRequest(requestId) {
        put("subtype", "apply_flag_settings")
        putJsonObject("settings") { put("effortLevel", effort) }
    }

    /**
     * Turns Remote Control on or off: while on, the session can be followed and continued from claude.ai or the Claude
     * app. Turning it on is answered with the session's `session_url`; [name] names the session there, null leaves it to
     * the CLI.
     */
    fun remoteControl(requestId: String, enabled: Boolean, name: String? = null): String = controlRequest(requestId) {
        put("subtype", "remote_control")
        put("enabled", enabled)
        name?.let { put("name", it) }
    }

    /** Refuses a control request the plugin can't serve. */
    fun controlError(requestId: String, error: String): String = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
            put("subtype", "error")
            put("request_id", requestId)
            put("error", error)
        }
    }.toString()

    private fun controlRequest(requestId: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request", body)
    }.toString()

    private fun controlResponse(requestId: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
            put("subtype", "success")
            put("request_id", requestId)
            putJsonObject("response", body)
        }
    }.toString()
}
