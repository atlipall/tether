package tether.testutil

import tether.cli.CliConnection
import tether.cli.PermissionMode
import tether.cli.arr
import tether.cli.obj
import tether.cli.parseJsonObject
import tether.cli.str
import tether.session.ChatItem
import tether.session.ChatListener
import tether.session.Choice
import tether.session.SessionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class FakeConnection : CliConnection {
    val sent = mutableListOf<String>()
    var stopped = false
    var started = false
    private var onLine: ((String) -> Unit)? = null
    private var onExit: ((Int, String) -> Unit)? = null

    override fun start(onLine: (String) -> Unit, onExit: (Int, String) -> Unit) {
        started = true
        this.onLine = onLine
        this.onExit = onExit
    }
    override fun send(line: String) { sent += line }
    override fun stop() { stopped = true }

    fun emit(vararg lines: String) = lines.forEach { onLine!!(it) }
    fun exit(code: Int, stderr: String) = onExit!!(code, stderr)

    fun sentJson(): List<JsonObject> = sent.map { parseJsonObject(it)!! }
    /** The most recent control_request with the given subtype. */
    fun lastRequest(subtype: String): JsonObject =
        sentJson().last { it.str("type") == "control_request" && it.obj("request")?.str("subtype") == subtype }
    /** request_id of the most recent control_request with the given subtype. */
    fun lastRequestId(subtype: String): String = lastRequest(subtype).str("request_id")!!
    /** How many control requests with the given subtype were sent. */
    fun requestCount(subtype: String): Int =
        sentJson().count { it.str("type") == "control_request" && it.obj("request")?.str("subtype") == subtype }

    /** Text of every user message: the string content, or the text blocks of a content array. */
    fun userMessages(): List<String> =
        sentJson().filter { it.str("type") == "user" }.map { msg ->
            val message = msg.obj("message")!!
            message.str("content") ?: message.arr("content")!!.map { it.jsonObject }.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text")!! }
        }

    /** The uuid each user message was sent with, in order. */
    fun userUuids(): List<String?> = sentJson().filter { it.str("type") == "user" }.map { it.str("uuid") }

    /** Base64 data of the image blocks of every user message (empty for a text-only message). */
    fun userImages(): List<List<String>> =
        sentJson().filter { it.str("type") == "user" }.map { msg ->
            msg.obj("message")!!.arr("content").orEmpty().map { it.jsonObject }.filter { it.str("type") == "image" }.map { it.obj("source")!!.str("data")!! }
        }
}

class RecordingListener(private val currentMode: (() -> String?)? = null) : ChatListener {
    val items = mutableListOf<ChatItem>()
    val liveItems = mutableListOf<ChatItem>()
    val historyItems = mutableListOf<ChatItem>()
    val states = mutableListOf<Triple<SessionState, String?, PermissionMode?>>()
    override fun onItem(item: ChatItem) { items += item; liveItems += item }
    override fun onHistoryItem(item: ChatItem) { items += item; historyItems += item }
    override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
        states += Triple(state, sessionId, mode)
        modeIds += currentMode?.invoke() ?: mode?.cli
    }
    val modes = mutableListOf<List<Choice>>()
    override fun onModes(modes: List<Choice>) { this.modes += modes }
    val modeIds = mutableListOf<String?>()
    val activities = mutableListOf<tether.session.Activity?>()
    override fun onActivity(activity: tether.session.Activity?) { activities += activity }
    val contexts = mutableListOf<tether.cli.ContextUsage>()
    override fun onContext(usage: tether.cli.ContextUsage) { contexts += usage }
    val models = mutableListOf<tether.cli.ModelChoice>()
    override fun onModel(choice: tether.cli.ModelChoice) { models += choice }
    val suggestions = mutableListOf<String>()
    override fun onSuggestion(text: String) { suggestions += text }
    val queues = mutableListOf<List<tether.session.QueuedMessage>>()
    override fun onQueue(messages: List<tether.session.QueuedMessage>) { queues += messages }
    val dropped = mutableListOf<List<tether.session.QueuedMessage>>()
    override fun onDropped(messages: List<tether.session.QueuedMessage>) { dropped += messages }
    val processes = mutableListOf<List<tether.session.BackgroundProcess>>()
    override fun onProcesses(processes: List<tether.session.BackgroundProcess>) { this.processes += processes }
    val remoteControls = mutableListOf<tether.session.RemoteControl>()
    override fun onRemoteControl(state: tether.session.RemoteControl) { remoteControls += state }
    fun queuedTexts(): List<String> = queues.lastOrNull().orEmpty().map { it.text }

    /** Latest version of every item, in first-seen order. */
    fun current(): List<ChatItem> = items.associateBy { it.id }.values.toList()
    inline fun <reified T : ChatItem> latest(): T = items.filterIsInstance<T>().last()
    fun lastState(): SessionState = states.last().first
}
