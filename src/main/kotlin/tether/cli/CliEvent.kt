package tether.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A base64 image content block as the CLI sent it: [mediaType] is what the CLI claims, [data] is not yet checked. */
data class ImageBlock(val mediaType: String?, val data: String)

/**
 * A task the CLI runs in the background. [type] is `local_agent` for a subagent, `local_bash` for a shell command,
 * `monitor`, `local_workflow` and so on; [ambient] marks housekeeping work the CLI started by itself.
 */
data class BackgroundTask(val taskId: String, val type: String, val description: String, val ambient: Boolean = false)

sealed interface CliEvent {
    data class SystemInit(val sessionId: String, val permissionMode: String?, val cwd: String?) : CliEvent
    data class TextDelta(val messageId: String, val index: Int, val text: String) : CliEvent
    /** A top-level assistant message began streaming (a subagent's messages are not reported). */
    data class MessageStart(val messageId: String) : CliEvent
    /**
     * A task began; [toolUseId] is the call that started it. [taskId] names it in a stop request; [taskType] is
     * `local_agent` for a subagent, `local_bash` for a shell command and so on, null from a CLI that predates it.
     */
    data class TaskStarted(
        val toolUseId: String,
        val subagentType: String?,
        val background: Boolean,
        val prompt: String?,
        val taskId: String? = null,
        val taskType: String? = null,
    ) : CliEvent
    data class TaskProgress(val toolUseId: String, val toolUses: Int?, val durationMs: Long?, val lastTool: String?) : CliEvent
    /** A subagent ended; [status] is "completed" on success. [report] is what it handed back. */
    data class TaskDone(val toolUseId: String, val status: String, val toolUses: Int?, val durationMs: Long?, val report: String?) : CliEvent
    /** Every background task (e.g. agents and shell commands started with run_in_background) the CLI is running now. */
    data class BackgroundTasks(val tasks: List<BackgroundTask>) : CliEvent
    /** A thinking content block began (its text is never shown). */
    data class ThinkingStart(val messageId: String, val index: Int) : CliEvent
    data class BlockStop(val messageId: String, val index: Int) : CliEvent
    /**
     * [parentId]: the Agent call whose subagent sent this; null for the main conversation (also below).
     * [commandOutput]: the CLI wrote this text itself as a local command's output (such as `/usage`), so it is plain
     * text laid out with line breaks and spaces rather than markdown.
     */
    data class AssistantText(val messageId: String, val text: String, val parentId: String? = null, val commandOutput: Boolean = false) : CliEvent
    /** A main-conversation assistant message was recorded under [uuid]; a rewind can resume the session at it. */
    data class AssistantMessage(val uuid: String) : CliEvent
    data class ToolUse(val toolUseId: String, val name: String, val input: JsonObject, val parentId: String? = null) : CliEvent
    data class ToolResult(
        val toolUseId: String,
        val isError: Boolean,
        val content: String,
        val filePath: String?,
        val originalFile: String?,
        val hasOriginalFile: Boolean,
        /** Image blocks inside the result's content (e.g. Read on a PNG), unvalidated. */
        val images: List<ImageBlock> = emptyList(),
        val parentId: String? = null,
    ) : CliEvent
    data class UserText(val text: String) : CliEvent
    data class PermissionRequest(val requestId: String, val toolName: String, val toolUseId: String?, val input: JsonObject) : CliEvent
    /** Claude's MCP traffic for in-process server [server]; a JSON-RPC request is answered with [CliMessages.mcpResponse]. */
    data class McpMessage(val requestId: String, val server: String, val message: JsonObject) : CliEvent
    /** A hook registered in the initialize request fired; answered with [CliMessages.hookResponse]. */
    data class HookCallback(val requestId: String, val callbackId: String, val input: JsonObject) : CliEvent
    data class ControlResponse(val requestId: String, val isSuccess: Boolean, val response: JsonObject?, val error: String?) : CliEvent
    data class Result(
        val isError: Boolean,
        val subtype: String,
        val text: String?,
        val durationMs: Long?,
        val costUsd: Double?,
        val errors: List<String>,
    ) : CliEvent
    /** The CLI's guess at the user's next prompt, sent after some turns' result. */
    data class PromptSuggestion(val text: String) : CliEvent
    data class Ignored(val type: String) : CliEvent
    data class Malformed(val line: String) : CliEvent
}

/** Turns one stdout line of `claude --output-format stream-json` into zero or more events. */
object CliParser {
    private val EMPTY = JsonObject(emptyMap())

    fun parse(line: String): List<CliEvent> {
        val obj = parseJsonObject(line) ?: return listOf(CliEvent.Malformed(line))
        return when (val type = obj.str("type")) {
            "system" ->
                when (obj.str("subtype")) {
                    "init" -> listOf(CliEvent.SystemInit(obj.str("session_id") ?: "", obj.str("permissionMode"), obj.str("cwd")))
                    "background_tasks_changed" -> listOf(CliEvent.BackgroundTasks(backgroundTasks(obj)))
                    "task_started", "task_progress", "task_notification" -> listOf(task(obj))
                    else -> listOf(CliEvent.Ignored("system/${obj.str("subtype")}"))
                }
            "stream_event" -> listOf(streamEvent(obj))
            "assistant" -> assistant(obj)
            "user" -> user(obj)
            "control_request" -> listOf(controlRequest(obj))
            "control_response" -> listOf(controlResponse(obj))
            "result" -> listOf(result(obj))
            "prompt_suggestion" -> listOf(CliEvent.PromptSuggestion(obj.str("suggestion") ?: ""))
            else -> listOf(CliEvent.Ignored(type ?: "?"))
        }
    }

    /** The entries of a background_tasks_changed event; one without an id or type can't be shown or stopped, so it is skipped. */
    private fun backgroundTasks(obj: JsonObject): List<BackgroundTask> = obj.arr("tasks").orEmpty().mapNotNull { entry ->
        val task = entry as? JsonObject ?: return@mapNotNull null
        val id = task.str("task_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val type = task.str("task_type")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        BackgroundTask(id, type, task.str("description").orEmpty(), task.bool("ambient") == true)
    }

    /** A task event; without the Agent call's id it can't be tied to a card, so it is ignored. */
    private fun task(obj: JsonObject): CliEvent {
        val subtype = obj.str("subtype")
        val id = obj.str("tool_use_id")?.takeIf { it.isNotEmpty() } ?: return CliEvent.Ignored("system/$subtype")
        val usage = obj.obj("usage")
        val toolUses = usage?.long("tool_uses")?.toInt()
        val durationMs = usage?.long("duration_ms")
        return when (subtype) {
            "task_started" -> CliEvent.TaskStarted(
                id, obj.str("subagent_type"), obj.bool("is_backgrounded") == true, obj.str("prompt"),
                obj.str("task_id")?.takeIf { it.isNotEmpty() }, obj.str("task_type")?.takeIf { it.isNotEmpty() },
            )
            "task_progress" -> CliEvent.TaskProgress(id, toolUses, durationMs, obj.str("last_tool_name"))
            else -> CliEvent.TaskDone(id, obj.str("status") ?: "", toolUses, durationMs, obj.obj("handback_report")?.str("text"))
        }
    }

    private fun streamEvent(obj: JsonObject): CliEvent {
        if (obj.str("parent_tool_use_id") != null) return CliEvent.Ignored("stream_event/subagent")
        val messageId = obj.str("api_message_id") ?: ""
        val event = obj.obj("event") ?: return CliEvent.Ignored("stream_event/?")
        val index = event.long("index")?.toInt() ?: 0
        return when (val type = event.str("type")) {
            "content_block_delta" -> {
                val delta = event.obj("delta")
                if (delta?.str("type") == "text_delta") CliEvent.TextDelta(messageId, index, delta.str("text") ?: "")
                else CliEvent.Ignored("stream_event/content_block_delta/${delta?.str("type")}")
            }
            "content_block_start" ->
                if (event.obj("content_block")?.str("type") == "thinking") CliEvent.ThinkingStart(messageId, index)
                else CliEvent.Ignored("stream_event/content_block_start")
            "content_block_stop" -> CliEvent.BlockStop(messageId, index)
            "message_start" -> CliEvent.MessageStart(messageId)
            else -> CliEvent.Ignored("stream_event/$type")
        }
    }

    private fun assistant(obj: JsonObject): List<CliEvent> {
        val message = obj.obj("message") ?: return emptyList()
        val messageId = message.str("id") ?: ""
        val parent = obj.str("parent_tool_use_id")
        val commandOutput = obj.containsKey("local_command_source")
        val blocks = message.arr("content").orEmpty().mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            when (b.str("type")) {
                "text" -> CliEvent.AssistantText(messageId, b.str("text") ?: "", parent, commandOutput)
                "tool_use" -> CliEvent.ToolUse(b.str("id") ?: "", b.str("name") ?: "", b.obj("input") ?: EMPTY, parent)
                else -> null
            }
        }
        val uuid = obj.str("uuid")?.takeIf { parent == null && it.isNotEmpty() } ?: return blocks
        return listOf(CliEvent.AssistantMessage(uuid)) + blocks
    }

    /** A subagent's user text is dropped: it is the subagent's prompt, which its task_started event already carries. */
    private fun user(obj: JsonObject): List<CliEvent> {
        val content = obj.obj("message")?.get("content") ?: return emptyList()
        val parent = obj.str("parent_tool_use_id")
        if (content is JsonPrimitive) return if (parent == null) listOf(CliEvent.UserText(content.content)) else emptyList()
        val blocks = (content as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val toolUseResult = obj["tool_use_result"] as? JsonObject
        val singleResult = blocks.count { it.str("type") == "tool_result" } == 1
        return blocks.mapNotNull { b ->
            when (b.str("type")) {
                "tool_result" -> {
                    val extra = if (singleResult) toolUseResult else null
                    val original = extra?.get("originalFile")
                    CliEvent.ToolResult(
                        toolUseId = b.str("tool_use_id") ?: "",
                        isError = b.bool("is_error") == true,
                        content = textOf(b["content"]),
                        filePath = extra?.str("filePath"),
                        originalFile = if (original == null || original is JsonNull) null else (original as? JsonPrimitive)?.content,
                        hasOriginalFile = extra?.containsKey("originalFile") == true,
                        images = imagesOf(b["content"]),
                        parentId = parent,
                    )
                }
                "text" -> if (parent == null) CliEvent.UserText(b.str("text") ?: "") else null
                else -> null
            }
        }
    }

    private fun textOf(content: Any?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
        else -> ""
    }

    /** `{"type":"image","source":{"type":"base64","media_type":…,"data":…}}` blocks of a tool result's content. */
    private fun imagesOf(content: Any?): List<ImageBlock> =
        (content as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it.str("type") == "image" }
            .mapNotNull { it.obj("source") }
            .filter { it.str("type") == "base64" }
            .mapNotNull { source -> source.str("data")?.let { ImageBlock(source.str("media_type"), it) } }

    private fun controlRequest(obj: JsonObject): CliEvent {
        val request = obj.obj("request") ?: return CliEvent.Ignored("control_request/?")
        return when (val subtype = request.str("subtype")) {
            "can_use_tool" -> CliEvent.PermissionRequest(
                requestId = obj.str("request_id") ?: "",
                toolName = request.str("tool_name") ?: "",
                toolUseId = request.str("tool_use_id"),
                input = request.obj("input") ?: EMPTY,
            )
            "mcp_message" -> CliEvent.McpMessage(
                requestId = obj.str("request_id") ?: "",
                server = request.str("server_name") ?: "",
                message = request.obj("message") ?: EMPTY,
            )
            "hook_callback" -> CliEvent.HookCallback(
                requestId = obj.str("request_id") ?: "",
                callbackId = request.str("callback_id") ?: "",
                input = request.obj("input") ?: EMPTY,
            )
            else -> CliEvent.Ignored("control_request/$subtype")
        }
    }

    private fun controlResponse(obj: JsonObject): CliEvent {
        val response = obj.obj("response") ?: return CliEvent.Ignored("control_response/?")
        return CliEvent.ControlResponse(
            requestId = response.str("request_id") ?: "",
            isSuccess = response.str("subtype") == "success",
            response = response.obj("response"),
            error = response.str("error"),
        )
    }

    private fun result(obj: JsonObject) = CliEvent.Result(
        isError = obj.bool("is_error") == true,
        subtype = obj.str("subtype") ?: "",
        text = obj.str("result"),
        durationMs = obj.long("duration_ms"),
        costUsd = obj.double("total_cost_usd"),
        errors = obj.arr("errors").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
    )
}
