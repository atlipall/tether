package tether.session

import tether.cli.bool
import tether.cli.obj
import tether.cli.parseJsonObject
import tether.cli.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

/**
 * A past session; [folder] is the cwd it ran in (its transcript lives under that folder's project dir). [title] is its
 * first message; [customTitle] the name it was given (in Tether or with Claude Code's `/rename`), if any. An [archived]
 * session is hidden from the resume list.
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val modifiedMillis: Long,
    val folder: String,
    val customTitle: String? = null,
    val archived: Boolean = false,
)

/** What a session's transcript holds for resuming it (see [SessionStore.read]); [summary] as the resume list shows it. */
data class Transcript(val items: List<ChatItem>, val lastAssistantUuid: String?, val startedAt: Long?, val summary: SessionSummary?)

/** Reads Claude Code's on-disk session transcripts (~/.claude/projects/<encoded cwd>/<id>.jsonl). */
class SessionStore(private val claudeHome: File) {
    fun projectDir(cwd: String): File = File(File(claudeHome, "projects"), cwd.replace(NON_ALNUM, "-"))

    private fun transcriptFile(cwd: String, sessionId: String) = File(projectDir(cwd), "$sessionId.jsonl")

    /** The folder's sessions, newest first: the archived ones when [archived], otherwise the rest. */
    fun list(cwd: String, limit: Int = 30, archived: Boolean = false): List<SessionSummary> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> cachedSummary(f, cwd) }
            .filter { it.archived == archived }
            .take(limit)
            .toList()

    /** Sessions of every folder, newest first. Folders that map to the same project dir are read once. */
    fun listAll(folders: List<String>, limit: Int = 50, archived: Boolean = false): List<SessionSummary> =
        folders.distinctBy { projectDir(it) }
            .flatMap { list(it, limit, archived) }
            .sortedByDescending { it.modifiedMillis }
            .take(limit)

    /**
     * Everything resuming [sessionId] needs from its transcript, from one pass over it: the messages shown, the uuid of
     * the newest main-conversation assistant message (subagents' are skipped), the first timestamp (epoch millis) and
     * its summary (which the resume list then reuses). Empty when the transcript is missing.
     */
    fun read(cwd: String, sessionId: String): Transcript {
        val file = transcriptFile(cwd, sessionId)
        if (!file.isFile) return Transcript(emptyList(), null, null, null)
        val modified = file.lastModified()
        val length = file.length()
        val items = mutableListOf<ChatItem>()
        var lastAssistant: String? = null
        var startedAt: Long? = null
        val title = TitleScan()
        file.useLines { lines ->
            for (line in lines) {
                if (startedAt != null && !mayShow(line)) continue
                val obj = parseJsonObject(line) ?: continue
                if (startedAt == null) startedAt = obj.str("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                when (obj.str("type")) {
                    "user" -> userText(obj)?.let {
                        if (title.first == null) title.first = it
                        items += ChatItem.UserText("h-${items.size}", it)
                    }
                    "assistant" -> {
                        assistantText(obj)?.let { items += ChatItem.AssistantText("h-${items.size}", it, false) }
                        if (obj.bool("isSidechain") != true) obj.str("uuid")?.let { lastAssistant = it }
                    }
                    CUSTOM_TITLE -> title.add(obj)
                    "system" -> localCommandItem(obj, "h-${items.size}")?.let { items += it }
                }
            }
        }
        val summary = title.summary(file, cwd, modified)
        scanned[file.path] = Scanned(modified, length, summary)
        return Transcript(items, lastAssistant, startedAt, summary)
    }

    /**
     * False for lines [read] has no use for, without parsing them: only user, assistant, title and local command lines
     * matter, and a user line of tool results alone shows nothing. JSON escapes quotes inside strings, so these patterns only match the
     * line's own structure; a line that matches by chance is merely parsed.
     */
    private fun mayShow(line: String): Boolean = when {
        line.contains(TITLE_TYPE) || line.contains(ASSISTANT_TYPE) || line.contains(LOCAL_COMMAND_SUBTYPE) -> true
        !line.contains(USER_TYPE) -> false
        else -> !line.contains(TOOL_RESULT_TYPE) || line.contains(TEXT_TYPE) || line.contains(IMAGE_TYPE)
    }

    /**
     * Names the session [title] the way Claude Code's `/rename` does: a `custom-title` line appended to its transcript
     * (the last one wins; blank means none). [archived] prefixes it with `[archived]`, as other Claude apps do.
     * Safe while claude appends to the same file: the line is written in one append-mode write, so the kernel places
     * it at the end even if claude appended in between. The parent folder is created because claude may not have
     * written the transcript yet; it appends to this one.
     */
    fun saveTitle(cwd: String, sessionId: String, title: String, archived: Boolean = false) {
        val file = transcriptFile(cwd, sessionId)
        file.parentFile.mkdirs()
        val line = buildJsonObject {
            put("type", CUSTOM_TITLE)
            put("customTitle", if (archived) "${ChatNames.ARCHIVED} $title".trimEnd() else title)
            put("sessionId", sessionId)
        }.toString()
        val midLine = try {
            RandomAccessFile(file, "r").use { f ->
                val end = f.length()
                end > 0 && f.run { seek(end - 1); read() != '\n'.code }
            }
        } catch (_: FileNotFoundException) {
            false
        }
        FileOutputStream(file, true).use { it.write(((if (midLine) "\n" else "") + line + "\n").toByteArray()) }
    }

    private class Scanned(val modified: Long, val length: Long, val summary: SessionSummary?)

    /** Summaries by transcript path, read again only when the file changes: finding the title means reading it all. */
    private val scanned = ConcurrentHashMap<String, Scanned>()

    private fun cachedSummary(file: File, cwd: String): SessionSummary? {
        val modified = file.lastModified()
        val length = file.length()
        scanned[file.path]?.takeIf { it.modified == modified && it.length == length }?.let { return it.summary }
        return summary(file, cwd, modified).also { scanned[file.path] = Scanned(modified, length, it) }
    }

    /**
     * The first message and the last custom title; null when the session has neither. A title alone is kept: an archived
     * chat's claude can be stopped before it writes the first message. Once the first message is found only title lines
     * are parsed.
     */
    private fun summary(file: File, cwd: String, modified: Long): SessionSummary? {
        val title = TitleScan()
        file.useLines { lines ->
            for (line in lines) {
                val titled = line.contains(TITLE_TYPE)
                if (title.first != null && !titled) continue
                val obj = parseJsonObject(line) ?: continue
                when (obj.str("type")) {
                    "user" -> if (title.first == null) title.first = userText(obj)
                    CUSTOM_TITLE -> title.add(obj)
                }
            }
        }
        return title.summary(file, cwd, modified)
    }

    /** What a summary is made of, gathered line by line: the first message and the last custom title. */
    private class TitleScan {
        var first: String? = null
        private var custom: String? = null
        private var archived = false

        fun add(titleLine: JsonObject) {
            val raw = titleLine.str("customTitle").orEmpty().trim()
            archived = raw.startsWith(ChatNames.ARCHIVED)
            custom = ChatNames.clean(raw)
        }

        /** [modified] is the stamp taken before reading, so the summary and the cache entry agree. */
        fun summary(file: File, cwd: String, modified: Long): SessionSummary? {
            val title = first?.let { ChatNames.clean(it)?.take(80) } ?: custom ?: return null
            return SessionSummary(file.nameWithoutExtension, title, modified, cwd, custom, archived)
        }
    }

    /** The text the user typed, under one `[image]` per image they attached (the plugin keeps no image data). */
    private fun userText(obj: JsonObject): String? {
        if (obj.bool("isMeta") == true || obj.bool("isSidechain") == true) return null
        val content = obj.obj("message")?.get("content")
        val text = textOf(content)
        if (text != null && (text.startsWith("<") || text.startsWith("[Request interrupted"))) return null
        val typed = text?.let(::stripIdeNote)
        val images = (content as? JsonArray).orEmpty().count { (it as? JsonObject)?.str("type") == "image" }
        if (images == 0) return typed
        val marks = List(images) { IMAGE_MARK }.joinToString(" ")
        return if (typed == null) marks else "$marks\n$typed"
    }

    private fun assistantText(obj: JsonObject): String? {
        if (obj.bool("isSidechain") == true) return null
        return textOf(obj.obj("message")?.get("content"))
    }

    private fun textOf(content: Any?): String? = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.filterIsInstance<JsonObject>()
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
        else -> null
    }?.takeIf { it.isNotBlank() }

    /**
     * A local command such as `/usage` from its `system` line: the line naming the command becomes the message the user
     * typed, and the line holding its output a [ChatItem.AssistantText] marked as command output. Claude writes no user
     * line for such a command. Null for any other system line, and for a command that printed nothing.
     */
    private fun localCommandItem(obj: JsonObject, id: String): ChatItem? {
        if (obj.str("subtype") != LOCAL_COMMAND || obj.bool("isSidechain") == true) return null
        val content = obj.str("content")?.trim().orEmpty()
        LOCAL_COMMAND_OUTPUT.find(content)?.let { output ->
            val text = output.groupValues[2].trimEnd().takeIf { it.isNotBlank() } ?: return null
            return ChatItem.AssistantText(id, text, false, commandOutput = true)
        }
        return typedCommand(content)?.let { ChatItem.UserText(id, it) }
    }

    /** The command as typed: `/name args` from a `<command-name>` line, or the line itself when it is already that. */
    private fun typedCommand(content: String): String? {
        val name = tagged(content, "command-name") ?: return content.takeIf { it.startsWith("/") }
        val args = tagged(content, "command-args").orEmpty()
        return if (args.isEmpty()) name else "$name $args"
    }

    /** The trimmed text between `<tag>` and `</tag>` in [content]; null when it has no such tag. */
    private fun tagged(content: String, tag: String): String? {
        val start = content.indexOf("<$tag>").takeIf { it >= 0 } ?: return null
        return content.substring(start + tag.length + 2).substringBefore("</$tag>").trim()
    }

    /** Drops every leading "[IDE] " note (a message can carry an edit note and a context note); null when only notes remain. */
    private fun stripIdeNote(text: String): String? {
        var rest = text
        while (rest.startsWith("[IDE] ")) rest = rest.substringAfter("\n\n", "")
        return rest.takeIf { it.isNotBlank() }
    }


    companion object {
        private val NON_ALNUM = Regex("[^A-Za-z0-9]")
        const val IMAGE_MARK = "[image]"
        private const val CUSTOM_TITLE = "custom-title"
        private const val LOCAL_COMMAND = "local_command"
        /** How a local command's system line spells its subtype. */
        private const val LOCAL_COMMAND_SUBTYPE = "\"subtype\":\"$LOCAL_COMMAND\""
        /** A local command's output, which claude wraps in a stdout or stderr tag. */
        private val LOCAL_COMMAND_OUTPUT = Regex("^<local-command-(stdout|stderr)>(.*)</local-command-\\1>$", RegexOption.DOT_MATCHES_ALL)
        /** How a title line spells its type: cheaper to find than parsing every line, and rarer than the bare word. */
        private const val TITLE_TYPE = "\"$CUSTOM_TITLE\""
        /** How a user line spells its type. Claude writes compact JSON, so this and the types below match without spaces. */
        private const val USER_TYPE = "\"type\":\"user\""
        private const val ASSISTANT_TYPE = "\"type\":\"assistant\""
        private const val TOOL_RESULT_TYPE = "\"type\":\"tool_result\""
        private const val TEXT_TYPE = "\"type\":\"text\""
        private const val IMAGE_TYPE = "\"type\":\"image\""

        fun default(): SessionStore {
            val home = System.getenv("CLAUDE_CONFIG_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".claude")
            return SessionStore(home)
        }
    }
}
