package tether.ui

import tether.cli.ContextUsage
import tether.cli.ModelChoice
import tether.cli.arr
import tether.cli.long
import tether.cli.parseJsonObject
import tether.cli.str
import tether.ide.CheckoutDiff
import tether.ide.FileLinker
import tether.ide.ResolvedPath
import tether.image.Attachment
import tether.image.Image
import tether.image.Images
import tether.image.Prepared
import tether.image.Uploads
import tether.context.ContextNotes
import tether.session.Activity
import tether.session.BackgroundProcess
import tether.session.Agent
import tether.session.Chat
import tether.session.ChatItem
import tether.session.Choice
import tether.session.FileDiff
import tether.session.PermissionDecision
import tether.session.QuestionAnswer
import tether.session.QueuedMessage
import tether.session.PromptHistory
import tether.session.SessionState
import tether.session.SessionView
import tether.session.toJson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Connects one chat page to one [Chat]: web-view messages in, chat updates out. */
class ChatBridge(
    private val project: Project,
    private val chat: Chat,
    private val post: (String) -> Unit,
) : SessionView {
    private val linker get() = FileLinker.getInstance(project)
    private val session get() = chat.session
    private val history get() = PromptHistory.getInstance(project)
    @Volatile private var unsubscribe: (() -> Unit)? = null

    /** Runs image work (decoding, scaling, reading files) off the EDT. Tests make it synchronous. */
    internal var offEdt: (() -> Unit) -> Unit = { task -> IMAGE_EXECUTOR.execute { task() } }

    /** Resolves the paths a reply mentions off the EDT, apart from image work so neither waits behind the other. Tests make it synchronous. */
    internal var pathsOffEdt: (() -> Unit) -> Unit = { task -> PATH_EXECUTOR.execute { task() } }

    /** Validates and downscales image bytes. Tests replace it. */
    internal var preparer: (ByteArray) -> Prepared = { Images.prepare(it) }

    /** Display copy of a local image for the page (see [FileLinker.localImage]). Tests replace it. */
    internal var localImage: (String, String?) -> String? = { path, cwd -> linker.localImage(path, cwd) }

    /** Brings results back to the EDT. Tests make it synchronous. */
    internal var onEdt: (() -> Unit) -> Unit = { task -> ApplicationManager.getApplication().invokeLater({ task() }, project.disposed) }

    /** Opens a file in the editor, as a file link does. Tests replace it. */
    internal var openInEditor: (VirtualFile) -> Unit = { OpenFileDescriptor(project, it).navigate(true) }

    /** Opens the checkout's changes (see [CheckoutDiff]). Tests replace it. */
    internal var showChanges: (Chat) -> Unit = { CheckoutDiff.show(project, it) }

    /** Opens a turn's file diffs at the [Int]th (see [FileLinker.showTurnDiffs]). Tests replace it. */
    internal var showTurnDiffs: (List<FileDiff>, Int) -> Unit = { diffs, index -> linker.showTurnDiffs(diffs, index) }

    /** Opens all of a turn's files in the changes browser (see [FileLinker.showTurnChanges]). Tests replace it. */
    internal var showTurnChanges: (List<FileDiff>) -> Unit = { linker.showTurnChanges(it) }

    /** Shows the IDE file chooser for images and calls back with the chosen files (EDT). Tests replace it. */
    internal var chooseImages: ((List<VirtualFile>) -> Unit) -> Unit = { done ->
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "webp", "heic", "heif")
            .withTitle("Attach Images")
        FileChooser.chooseFiles(descriptor, project, null) { files -> done(files) }
    }

    private val uploads = Uploads()
    /** Ids whose bytes are being prepared off the EDT; a `detach` (or a page reload) removes one so its result is dropped. EDT only. */
    private val preparing = HashSet<String>()
    private var pickSeq = 0

    /** EDT only. */
    fun handle(json: String) {
        val msg = parseJsonObject(json) ?: return
        when (msg.str("type")) {
            "ready" -> {
                post(Theme.message())
                post(historyMessage(history.entries()))
                if (unsubscribe == null) unsubscribe = history.subscribe { post(historyMessage(it)) }
                forgetAttachments()
                chat.attach(this)
            }
            "send" -> {
                val text = msg.str("text").orEmpty()
                val images = chat.attachments.take(strings(msg["attachments"]))
                if (text.isBlank() && images.isEmpty()) return
                remember(text)
                chat.send(text, images)
            }
            "attachStart" -> {
                val id = msg.str("id")?.takeIf(ID::matches) ?: return
                val name = displayName(msg.str("name"))
                val size = msg.long("size") ?: 0
                when {
                    slotsUsed() >= Images.MAX_PER_MESSAGE -> refuse(id, "Couldn't attach $name: at most ${Images.MAX_PER_MESSAGE} images per message.")
                    size > Images.MAX_INPUT_BYTES -> refuse(id, "Couldn't attach $name: it is larger than 20 MB.")
                    else -> uploads.start(id, name)
                }
            }
            "attachChunk" -> {
                val id = msg.str("id") ?: return
                if (uploads.has(id) && !uploads.chunk(id, msg.str("data").orEmpty())) {
                    refuse(id, "Couldn't attach the image: it is larger than 20 MB or could not be read.")
                }
            }
            "attachEnd" -> {
                val id = msg.str("id") ?: return
                val (name, bytes) = uploads.finish(id) ?: return
                prepare(id, name) { bytes }
            }
            "detach" -> msg.str("id")?.let { id ->
                uploads.cancel(id)
                preparing.remove(id)
                chat.attachments.remove(id)
            }
            "pickImages" -> chooseImages { files -> files.forEach(::attachFile) }
            "permission" -> {
                val requestId = msg.str("requestId") ?: return
                val decision = msg.str("optionId")?.let(PermissionDecision::Option) ?: when (msg.str("decision")) {
                    "allow" -> PermissionDecision.Allow
                    "allowSession" -> PermissionDecision.AllowForSession
                    else -> PermissionDecision.Deny(msg.str("message") ?: "The user denied this action.")
                }
                session.respond(requestId, decision)
            }
            "answer" -> {
                val requestId = msg.str("requestId") ?: return
                val answers = questionAnswers(msg["answers"]) ?: return session.answerFailed(requestId)
                session.answer(requestId, answers)
            }
            "skipQuestion" -> {
                val requestId = msg.str("requestId") ?: return
                if (session.pendingQuestions(requestId) != null) session.respond(requestId, PermissionDecision.Deny("The user skipped the question."))
            }
            "setMode" -> msg.str("mode")?.let(session::setMode)
            "setAgent" -> msg.str("agent")?.let(chat::selectAgent)
            "setModel" -> msg.str("model")?.takeIf { it.isNotBlank() }?.let(session::setModel)
            "setEffort" -> msg.str("effort")?.takeIf { it.isNotBlank() }?.let(session::setEffort)
            "interrupt" -> chat.interrupt()
            "stopTask" -> msg.str("id")?.takeIf(TASK_ID::matches)?.let(session::stopTask)
            "queueSendNow" -> msg.str("id")?.let(session::sendNow)
            "queueEdit" -> msg.str("id")?.let(chat::editQueued)
            "queueRemove" -> msg.str("id")?.let(session::unqueue)
            "restart" -> session.restart()
            "openFile" -> msg.str("target")?.let { linker.open(it, msg.long("line")?.toInt()) }
            "showChanges" -> showChanges(chat)
            "revertTo" -> msg.str("id")?.let(chat::revertTo)
            "openDiff" -> msg.str("toolUseId")?.let { id -> session.diffFor(id)?.let(linker::showDiff) }
            "openTurnDiff" -> turnDiffs(msg)?.let { diffs ->
                val index = msg.long("index")?.toInt() ?: 0
                if (index in diffs.indices) showTurnDiffs(diffs, index) else session.notice(TURN_TOO_OLD, isError = false)
            }
            "openTurnChanges" -> turnDiffs(msg)?.let(showTurnChanges)
            "openUrl" -> msg.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "resolvePaths" -> resolvePaths(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.distinct().take(MAX_PATH_CANDIDATES))
            "openImage" -> openImage(msg)
            "resolveImages" -> resolveImages(msg.long("reqId") ?: 0, strings(msg["paths"]).distinct().take(MAX_IMAGE_PATHS))
        }
    }

    /**
     * Opens the image the page's `openImage` names in the editor: image `index` of user message `item` (the file
     * written when it was attached), image `index` of tool call `tool` (see [toolImageFile]), or the markdown image at
     * `path` (found as [FileLinker.imageFileAt] finds it). Files are found, written and refreshed off the EDT; nothing
     * opens when the image has no file or its path is refused.
     */
    private fun openImage(msg: JsonObject) {
        val index = msg.long("index")?.toInt() ?: 0
        val cwd = chat.cwd
        val item = msg.str("item")
        val tool = msg.str("tool")
        val path = msg.str("path")
        val find: () -> VirtualFile? = when {
            item != null -> session.imageFile(item, index)?.let { file -> { localFile(Path.of(file)) } } ?: return
            tool != null -> session.toolCall(tool)?.let { call -> { toolImageFile(call, index, cwd) } } ?: return
            path != null -> { { linker.imageFileAt(path, cwd) } }
            else -> return
        }
        pathsOffEdt {
            val file = find()
            if (file != null) onEdt { openInEditor(file) }
        }
    }

    /**
     * Image [index] of tool call [call] as a file: the file the tool read when its path passes [FileLinker.imageFileAt]
     * (the path comes from the agent, so it is held to the same rules as a reply's image), else a copy of the image
     * written once to the chat's [Chat.imageFiles]. Off the EDT.
     */
    private fun toolImageFile(call: ChatItem.ToolCall, index: Int, cwd: String?): VirtualFile? {
        val src = call.images.getOrNull(index) ?: return null
        call.filePath?.let { linker.imageFileAt(it, cwd) }?.let { return it }
        val image = Image.fromDataUrl(src) ?: return null
        return chat.imageFiles.write("image", image.type, image.bytes, key = "tool:${call.id}:$index")?.let(::localFile)
    }

    /** The local file at [path], refreshed into the VFS. Off the EDT. */
    private fun localFile(path: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

    /** Forgets every attachment, started upload and preparation: a (re)loaded page shows none. */
    private fun forgetAttachments() {
        uploads.clear()
        preparing.clear()
        chat.attachments.clear()
    }

    /** Adds [text] to the input history, unless it is blank (history keeps text only) or the chat is dead ([Chat.send] ignores it). */
    private fun remember(text: String) {
        if (session.state != SessionState.DEAD && text.isNotBlank()) history.add(text)
    }

    /**
     * Answers request [reqId] with the local images at [paths], read off the EDT. A path that can't be read is left
     * out, so the page shows its alt text while the others are still answered; the answer goes out even when a
     * cancellation is being rethrown.
     */
    private fun resolveImages(reqId: Long, paths: List<String>) {
        val cwd = chat.cwd
        offEdt {
            val found = ArrayList<Pair<String, String>>()
            try {
                for (path in paths) {
                    try {
                        localImage(path, cwd)?.let { found += path to it }
                    } catch (e: Throwable) {
                        if (e is ControlFlowException) throw e
                    }
                }
            } finally {
                onEdt { post(imagesMessage(reqId, found)) }
            }
        }
    }

    private fun slotsUsed() = chat.attachments.size + uploads.count + preparing.size

    private fun attachFile(file: VirtualFile) =
        attachLocal(file.name) { if (file.length > Images.MAX_INPUT_BYTES) null else VfsUtilCore.loadBytes(file) }

    /** Whether files dropped on the panel are taken: not once the chat is dead, as the page's message box is then disabled. */
    fun acceptsDrops(): Boolean = session.state != SessionState.DEAD

    /**
     * Attaches files dropped on the panel from outside the page (Finder, the Project view), as the 📎 picker does. A
     * folder or other non-file is refused with a notice and no tile. Ignored when [acceptsDrops] is false. EDT only.
     */
    fun attachDropped(files: List<File>) {
        if (!acceptsDrops()) return
        for (file in files) {
            if (file.isFile) attachLocal(file.name) { readCapped(file) }
            else chat.notice("Couldn't attach ${displayName(file.name)}: ${Images.NOT_AN_IMAGE}.")
        }
    }

    /** Tells the page whether files are being dragged over the panel, so it can show where they will land. */
    fun dropHover(active: Boolean) = post(buildJsonObject {
        put("type", "dropTarget")
        put("active", active)
    }.toString())

    /**
     * The file's bytes, or null when it holds more than [Images.MAX_INPUT_BYTES]. Reads at most one byte past the cap,
     * so a file that grows after it was dropped can't be read in full.
     */
    private fun readCapped(file: File): ByteArray? =
        file.inputStream().use { it.readNBytes(Images.MAX_INPUT_BYTES + 1) }.takeIf { it.size <= Images.MAX_INPUT_BYTES }

    /**
     * Shows a loading tile for a local file and prepares its bytes ([read], off the EDT; null when over 20 MB), or
     * refuses it with a notice when the message already has its fill of images.
     */
    private fun attachLocal(fileName: String, read: () -> ByteArray?) {
        val id = "k${++pickSeq}"
        val name = displayName(fileName)
        if (slotsUsed() >= Images.MAX_PER_MESSAGE) {
            chat.notice("Couldn't attach $name: at most ${Images.MAX_PER_MESSAGE} images per message.")
            return
        }
        post(buildJsonObject {
            put("type", "attachment")
            put("id", id)
            put("name", name)
            put("loading", true)
        }.toString())
        prepare(id, name, read)
    }

    /**
     * Validates and (if needed) downscales the bytes off the EDT, makes its thumbnail (from a display copy) and writes
     * the image to the chat's [Chat.imageFiles]; then adds the attachment and shows its thumbnail, or refuses it with a
     * notice. [read] runs off the EDT; null
     * means the file is larger than 20 MB.
     */
    private fun prepare(id: String, name: String, read: () -> ByteArray?) {
        preparing += id
        offEdt {
            var thumb = ""
            var file = ""
            val outcome = try {
                val bytes = read()
                if (bytes == null) {
                    Prepared.Rejected("it is larger than 20 MB")
                } else {
                    val prepared = preparer(bytes)
                    if (prepared is Prepared.Ok) {
                        val display = Images.preview(prepared.image.bytes)
                        thumb = Images.preview((display ?: prepared.image).bytes, Images.THUMB_EDGE, Images.THUMB_BYTES)?.dataUrl().orEmpty()
                        file = chat.imageFiles.write(name, prepared.image.type, prepared.image.bytes)?.toString().orEmpty()
                    }
                    prepared
                }
            } catch (e: Throwable) {
                if (e is ControlFlowException) {
                    onEdt { if (preparing.remove(id)) refuse(id, "Couldn't attach $name: cancelled.") }
                    throw e
                }
                Prepared.Rejected(if (e is OutOfMemoryError) "the image is too large to process" else Images.UNREADABLE)
            }
            onEdt {
                if (!preparing.remove(id)) return@onEdt
                when (outcome) {
                    is Prepared.Rejected -> refuse(id, "Couldn't attach $name: ${outcome.reason}.")
                    is Prepared.Ok ->
                        if (chat.attachments.add(Attachment(id, name, outcome.image, thumb, file))) post(attachmentMessage(id, name, thumb))
                        else refuse(id, "Couldn't attach $name: at most ${Images.MAX_PER_MESSAGE} images per message.")
                }
            }
        }
    }

    /** Tells the page the attachment is gone and the chat why. */
    private fun refuse(id: String, notice: String) {
        uploads.cancel(id)
        chat.notice(notice)
        post(buildJsonObject {
            put("type", "attachment")
            put("id", id)
            put("failed", true)
        }.toString())
    }

    private fun attachmentMessage(id: String, name: String, thumb: String) = buildJsonObject {
        put("type", "attachment")
        put("id", id)
        put("name", name)
        put("thumb", thumb)
    }.toString()

    /** Stops history updates and drops abandoned uploads and in-flight preparations, which go with the page. Called when the page goes away. */
    fun dispose() {
        unsubscribe?.invoke()
        unsubscribe = null
        uploads.clear()
        preparing.clear()
    }

    /** One `reset` message, then `items` messages, each at most [BATCH_CHARS] long (images make items large). */
    override fun reset(items: List<ChatItem>) {
        val batches = batches(items.map { it.toJson() }, BATCH_CHARS)
        post(buildJsonObject {
            put("type", "reset")
            put("items", JsonArray(batches.firstOrNull().orEmpty()))
        }.toString())
        for (batch in batches.drop(1)) post(buildJsonObject {
            put("type", "items")
            put("items", JsonArray(batch))
        }.toString())
    }

    override fun item(item: ChatItem) = post(buildJsonObject {
        put("type", "item")
        put("item", item.toJson())
    }.toString())

    override fun state(state: SessionState, mode: String?) = post(buildJsonObject {
        put("type", "state")
        put("state", state.name)

        put("mode", mode)
    }.toString())

    override fun modes(modes: List<Choice>) = post(buildJsonObject {
        put("type", "modes")
        put("modes", JsonArray(modes.map { it.toJson() }))
    }.toString())

    override fun agent(agents: List<Agent>, selected: Agent, locked: Boolean) = post(buildJsonObject {
        put("type", "agent")
        put("agents", JsonArray(agents.map { Choice(it.id, it.name).toJson() }))
        put("selected", selected.id)
        put("locked", locked)
    }.toString())

    override fun activity(activity: Activity?) = post(activityMessage(activity))

    override fun context(usage: ContextUsage) = post(usageMessage(usage))

    override fun model(choice: ModelChoice) = post(modelMessage(choice))

    override fun suggestion(text: String) = post(buildJsonObject {
        put("type", "suggestion")
        put("text", text)
    }.toString())

    override fun processes(processes: List<BackgroundProcess>) = post(processesMessage(processes))

    override fun queue(messages: List<QueuedMessage>) = post(buildJsonObject {
        put("type", "queue")
        put("items", JsonArray(messages.map { ChatItem.UserText(it.id, it.text, it.images).toJson() }))
    }.toString())

    override fun insert(text: String) = post(buildJsonObject {
        put("type", "insert")
        put("text", text)
    }.toString())

    override fun checkout(text: String) = post(buildJsonObject {
        put("type", "checkout")
        put("text", text)
    }.toString())

    /** `{ "<question>": { "labels": [..], "other": ".." } }`, or null if the shape is wrong anywhere (the session checks the content). */
    private fun questionAnswers(element: JsonElement?): Map<String, QuestionAnswer>? {
        val map = element as? JsonObject ?: return null
        val out = LinkedHashMap<String, QuestionAnswer>()
        for ((question, value) in map) {
            val a = value as? JsonObject ?: return null
            val labelsRaw = a["labels"] as? JsonArray ?: return null
            val labels = strings(labelsRaw)
            if (labels.size != labelsRaw.size) return null
            val otherRaw = a["other"]
            if (otherRaw != null && otherRaw !is JsonNull && a.str("other") == null) return null
            out[question] = QuestionAnswer(labels, a.str("other"))
        }
        return out
    }

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun imagesMessage(reqId: Long, found: List<Pair<String, String>>) = buildJsonObject {
        put("type", "images")
        put("reqId", reqId)
        putJsonObject("images") { for ((path, url) in found) put(path, url) }
    }.toString()

    private fun historyMessage(entries: List<String>) = buildJsonObject {
        put("type", "history")
        put("entries", JsonArray(entries.map(::JsonPrimitive)))
    }.toString()

    /**
     * Answers the page with the [candidates] that are files, resolved [pathsOffEdt] because a lookup that misses refreshes
     * the file system. The page is always answered, with what was found, even when the lookup fails.
     */
    private fun resolvePaths(reqId: Long, candidates: List<String>) {
        val cwd = chat.cwd
        pathsOffEdt {
            val found = LinkedHashMap<String, ResolvedPath>()
            try {
                for (candidate in candidates) linker.resolve(candidate, cwd)?.let { found[candidate] = it }
            } finally {
                onEdt { post(resolvedMessage(reqId, found)) }
            }
        }
    }

    private fun resolvedMessage(reqId: Long, found: Map<String, ResolvedPath>) = buildJsonObject {
        put("type", "resolved")
        put("reqId", reqId)
        putJsonObject("paths") {
            for ((candidate, resolved) in found) put(candidate, buildJsonObject { put("target", resolved.target); put("line", resolved.line) })
        }
    }.toString()

    /** The diffs of the turn [msg] names, or null after saying the turn is too old to have them. */
    private fun turnDiffs(msg: JsonObject): List<FileDiff>? =
        msg.str("id")?.let(session::turnDiffs).orEmpty().ifEmpty { session.notice(TURN_TOO_OLD, isError = false); null }

    internal companion object {
        const val BATCH_CHARS = 2_000_000

        /** Longest tool name shown in the strip. */
        const val MAX_TOOL_NAME = 40

        const val TURN_TOO_OLD = "That turn is too old: its changes are no longer kept."

        /**
         * `{type:"activity", phase, tool?}`; a null phase hides the strip. Background work alone shows no strip: it has
         * rows of its own above it. The tool name is cleaned like the editor-context names.
         */
        fun activityMessage(activity: Activity?): String = buildJsonObject {
            put("type", "activity")
            put("phase", when (activity) {
                null, is Activity.Agents -> null
                Activity.Starting -> "starting"
                Activity.Thinking -> "thinking"
                is Activity.Running -> "running"
                Activity.Writing -> "writing"
                Activity.Waiting -> "waiting"
            })
            if (activity is Activity.Running) put("tool", ContextNotes.clean(activity.tool, MAX_TOOL_NAME).ifEmpty { "tool" })
        }.toString()

        /** Longest background process description sent to the page. */
        const val MAX_PROCESS_TEXT = 200

        /** A task id the page may ask to stop: the CLI's ids are short and plain. */
        private val TASK_ID = Regex("[A-Za-z0-9_.-]{1,64}")

        /** `{type:"processes", processes:[{id, kind, description, startedAt}]}`: the rows above the activity strip. */
        fun processesMessage(processes: List<BackgroundProcess>): String = buildJsonObject {
            put("type", "processes")
            putJsonArray("processes") {
                for (p in processes) addJsonObject {
                    put("id", p.id)
                    put("kind", ContextNotes.clean(p.kind, MAX_TOOL_NAME).ifEmpty { "Task" })
                    put("description", ContextNotes.clean(p.description, MAX_PROCESS_TEXT))
                    put("startedAt", p.startedAt)
                }
            }
        }.toString()

        /** Longest category name shown in the context tooltip. */
        const val MAX_CATEGORY_NAME = 40
        /** Longest model name or description shown in the picker. */
        const val MAX_MODEL_TEXT = 60

        /**
         * The composer's model and effort pickers, with the chip's [ModelChoice.modelName]; it and the option names and
         * descriptions are cleaned like context category names.
         */
        fun modelMessage(choice: ModelChoice): String = buildJsonObject {
            put("type", "model")
            put("model", choice.model)
            put("modelName", ContextNotes.clean(choice.modelName, MAX_MODEL_TEXT).ifEmpty { choice.model })
            choice.effort?.let { put("effort", it) }
            putJsonArray("efforts") { choice.efforts.forEach { add(it) } }
            putJsonArray("options") {
                for (o in choice.options) addJsonObject {
                    put("value", o.value)
                    put("name", ContextNotes.clean(o.name, MAX_MODEL_TEXT).ifEmpty { o.value })
                    o.description?.let { ContextNotes.clean(it, MAX_MODEL_TEXT) }?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                }
            }
        }.toString()

        /** `{type:"usage", used, max, percent, autocompactAt?, categories:[{name, tokens}]}`; names are cleaned like tool names. */
        fun usageMessage(usage: ContextUsage): String = buildJsonObject {
            put("type", "usage")
            put("used", usage.used)
            put("max", usage.max)
            put("percent", usage.percent)
            usage.autocompactAt?.let { put("autocompactAt", it) }
            putJsonArray("categories") {
                for (c in usage.categories) {
                    val name = ContextNotes.clean(c.name, MAX_CATEGORY_NAME)
                    if (name.isNotEmpty()) addJsonObject {
                        put("name", name)
                        put("tokens", c.tokens)
                    }
                }
            }
        }.toString()

        /** All image work (decoding, scaling, reading files) runs here, one task at a time. */
        private val IMAGE_EXECUTOR = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tether images", 1)
        /** Local images one reply may ask for at a time. */
        private val PATH_EXECUTOR = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tether paths", 1)
        const val MAX_IMAGE_PATHS = 20
        const val MAX_PATH_CANDIDATES = 200

        /** Consecutive groups of [items] whose JSON adds up to at most [limit] characters; a larger item goes alone. */
        fun batches(items: List<JsonObject>, limit: Int): List<List<JsonObject>> {
            val out = mutableListOf<MutableList<JsonObject>>()
            var size = 0
            for (item in items) {
                val length = item.toString().length
                if (out.isEmpty() || size + length > limit) {
                    out += mutableListOf<JsonObject>()
                    size = 0
                }
                out.last() += item
                size += length
            }
            return out
        }

        private val ID = Regex("[A-Za-z0-9_-]{1,32}")

        /** A file name for notices: plain text, one line, at most 80 characters. */
        private fun displayName(raw: String?): String =
            raw?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "image"
    }
}
