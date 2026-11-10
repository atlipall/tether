package tether.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolStatus { RUNNING, AWAITING_PERMISSION, DONE, ERROR, DENIED }

enum class AgentState { RUNNING, DONE, FAILED }

/** What the CLI's task events told about the subagent an Agent call started. */
data class AgentInfo(
    val subagentType: String?,
    val background: Boolean,
    val prompt: String?,
    val state: AgentState,
    val toolUses: Int? = null,
    val durationMs: Long? = null,
    /** The tool the subagent used last, while it runs. */
    val lastTool: String? = null,
    val report: String? = null,
    /** The CLI's id for the subagent's task, which a stop request names; null until its task_started event. */
    val taskId: String? = null,
)

/**
 * Work the agent runs in the background apart from subagents (which show as their Agent call): a shell command, a
 * monitor or a workflow. [id] is the CLI's task id; [kind] is what the row is labelled, e.g. "Bash"; [startedAt] is
 * when Tether first heard of it, in epoch milliseconds.
 */
data class BackgroundProcess(val id: String, val kind: String, val description: String, val startedAt: Long)

/** An option an agent offers: a permission answer or a mode. */
data class Choice(val id: String, val label: String)

/** A file a turn edited, by absolute path: lines added and removed since the turn began; [isNew] when the turn created it. */
data class TurnFile(val path: String, val added: Int, val removed: Int, val isNew: Boolean)

/** One entry in the chat log. Items are upserted by [id]. A `parentId` is the Agent call whose subagent produced the item. */
sealed interface ChatItem {
    val id: String

    /**
     * [images]: thumbnails of the attached images as `data:` URLs ("" for one that has no thumbnail); a click opens
     * the image itself ([AgentSession.imageFile]) in the editor. [revertable]:
     * the IDE marked the files' state when this message went out, so they can be reverted to it.
     */
    data class UserText(override val id: String, val text: String, val images: List<String> = emptyList(), val revertable: Boolean = false) : ChatItem
    /** [commandOutput]: a local command's plain-text output (see [tether.cli.CliEvent.AssistantText]), shown as written. */
    data class AssistantText(override val id: String, val text: String, val streaming: Boolean, val parentId: String? = null, val commandOutput: Boolean = false) : ChatItem
    data class ToolCall(
        override val id: String,
        val name: String,
        val input: JsonObject,
        val filePath: String?,
        val status: ToolStatus,
        val permissionRequestId: String?,
        val resultPreview: String?,
        val hasDiff: Boolean,
        /** Images in the tool's result as `data:` URLs of display copies ("" for one that can't be shown). */
        val images: List<String> = emptyList(),
        /** Set for an AskUserQuestion call that is waiting for (or has received) the user's answers. */
        val question: QuestionCard? = null,
        val parentId: String? = null,
        /** Set for an Agent/Task call once the CLI reports on its subagent. */
        val agent: AgentInfo? = null,
        /** The answers an ACP agent offers for its permission request; empty shows Claude's Allow / Deny buttons. */
        val choices: List<Choice> = emptyList(),
    ) : ChatItem
    /** The end of a turn; [files] are those its edit tools and Bash calls changed, in the order it first changed them. */
    data class TurnResult(
        override val id: String, val isError: Boolean, val text: String?, val durationMs: Long?, val costUsd: Double?,
        val files: List<TurnFile> = emptyList(),
    ) : ChatItem
    data class Notice(override val id: String, val text: String, val isError: Boolean) : ChatItem
}

fun ChatItem.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    when (val item = this@toJson) {
        is ChatItem.UserText -> {
            put("kind", "user")
            put("text", item.text)
            if (item.images.isNotEmpty()) put("images", JsonArray(item.images.map(::JsonPrimitive)))
            if (item.revertable) put("revertable", true)
        }
        is ChatItem.AssistantText -> {
            put("kind", "assistant")
            put("text", item.text)
            put("streaming", item.streaming)
            item.parentId?.let { put("parentId", it) }
            if (item.commandOutput) put("commandOutput", true)
        }
        is ChatItem.ToolCall -> {
            put("kind", "tool")
            put("name", item.name)
            put("input", item.input)
            put("filePath", item.filePath)
            put("status", item.status.name.lowercase())
            put("permissionRequestId", item.permissionRequestId)
            put("resultPreview", item.resultPreview)
            put("hasDiff", item.hasDiff)
            if (item.images.isNotEmpty()) put("images", JsonArray(item.images.map(::JsonPrimitive)))
            item.question?.let { put("question", it.toJson()) }
            item.parentId?.let { put("parentId", it) }
            item.agent?.let { a ->
                put("agent", buildJsonObject {
                    put("subagentType", a.subagentType)
                    put("background", a.background)
                    put("prompt", a.prompt)
                    put("state", a.state.name.lowercase())
                    put("toolUses", a.toolUses)
                    put("durationMs", a.durationMs)
                    put("lastTool", a.lastTool)
                    put("report", a.report)
                    put("taskId", a.taskId)
                })
            }
            if (item.choices.isNotEmpty()) put("choices", JsonArray(item.choices.map { it.toJson() }))
        }
        is ChatItem.TurnResult -> {
            put("kind", "result")
            put("isError", item.isError)
            put("text", item.text)
            put("durationMs", item.durationMs)
            put("costUsd", item.costUsd)
            if (item.files.isNotEmpty()) put("files", buildJsonArray {
                for (f in item.files) add(buildJsonObject {
                    put("path", f.path); put("added", f.added); put("removed", f.removed); put("isNew", f.isNew)
                })
            })
        }
        is ChatItem.Notice -> { put("kind", "notice"); put("text", item.text); put("isError", item.isError) }
    }
}

fun Choice.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("label", label)
}
