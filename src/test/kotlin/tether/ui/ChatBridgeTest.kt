package tether.ui

import kotlinx.serialization.json.jsonObject
import tether.testutil.Fx
import tether.cli.arr
import tether.cli.CliConnection
import tether.cli.bool
import tether.cli.long
import tether.cli.obj
import tether.cli.parseJsonObject
import tether.cli.str
import tether.context.EditorContextService
import tether.session.Chat
import tether.session.ChatSpec
import tether.session.Chats
import tether.session.PromptHistory
import tether.session.SessionState
import tether.settings.TetherSettings
import tether.testutil.FakeConnection
import tether.testutil.TestImages
import com.intellij.openapi.application.WriteAction
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import tether.image.ImageFiles
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import java.util.Base64
import kotlin.io.path.createTempDirectory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatBridgeTest : BasePlatformTestCase() {
    /** What the bridge posted; appended off the EDT too (session events arrive on reader threads). */
    private val posted = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    private val connections = mutableListOf<FakeConnection>()
    private val acp = mutableListOf<Pair<List<String>, FakeConnection>>()
    private lateinit var chat: Chat
    private lateinit var bridge: ChatBridge
    private val chats get() = Chats.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    private val settings get() = TetherSettings.getInstance().state
    private val contextService get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private var savedAttach = true
    private lateinit var savedSchedule: (Runnable) -> Unit
    private var savedAcpCommand: String? = null
    private var savedLastAgent: String? = null
    private lateinit var savedAcpConnector: (List<String>, String) -> CliConnection

    /** Starts with no editor open, unless a test opens one. */
    override fun setUp() {
        super.setUp()
        PromptHistory.getInstance(project).clear()
        chats.closeAll()
        chats.commandCatalog.clear()
        manager.openFiles.forEach(manager::closeFile)
        savedAttach = settings.attachEditorContext
        settings.attachEditorContext = true
        savedSchedule = contextService.schedule
        contextService.schedule = { it.run() }
        chats.connector = { FakeConnection().also { connections += it } }
        savedAcpConnector = chats.acpConnector
        chats.acpConnector = { command, _ -> FakeConnection().also { acp += command to it } }
        savedAcpCommand = settings.acpCommand
        settings.acpCommand = "hermes acp"
        savedLastAgent = settings.lastAgent
        settings.lastAgent = ""
        chat = chats.open(ChatSpec.inProject(root))
        bridge = ChatBridge(project, chat) { posted += parseJsonObject(it)!! }
        bridge.offEdt = { it() }
        bridge.pathsOffEdt = { it() }
        bridge.onEdt = { it() }
        bridge.openInEditor = { opened += it }
    }

    override fun tearDown() {
        try {
            bridge.dispose()
            chat.detach(bridge)
            chats.closeAll()
            manager.openFiles.forEach(manager::closeFile)
            settings.attachEditorContext = savedAttach
            contextService.schedule = savedSchedule
            chats.acpConnector = savedAcpConnector
            settings.acpCommand = savedAcpCommand
            settings.lastAgent = savedLastAgent
        } finally {
            super.tearDown()
        }
    }

    private val opened = mutableListOf<VirtualFile>()

    private fun types() = posted.map { it.str("type") }

    fun testReadySendsThemeHistoryResetAndState() {
        PromptHistory.getInstance(project).add("earlier")
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "history", "reset", "agent", "modes", "state"), types())
        assertEquals(listOf(JsonPrimitive("earlier")), posted[1].arr("entries")!!.toList())
        assertEquals("IDLE", posted.last().str("state"))
    }

    fun testSendReachesThisChatsCliOnly() {
        chat.start()
        val other = chats.open(ChatSpec("wt-1", "$root/.claude/worktrees/wt-1")).also { it.start() }
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertTrue(connections[0].userMessages().single().endsWith("hello"))
        assertTrue(connections[1].userMessages().isEmpty())
        assertEquals("user", posted.last { it.str("type") == "item" }.obj("item")!!.str("kind"))
        assertEquals("RUNNING", posted.last { it.str("type") == "state" }.str("state"))
        assertEquals(SessionState.IDLE, other.session.state)
    }

    fun testShowChangesOpensThisChatsChanges() {
        val shown = mutableListOf<Chat>()
        bridge.showChanges = { shown += it }
        bridge.handle("""{"type":"showChanges"}""")
        assertEquals(listOf(chat), shown)
    }

    fun testATurnsFileOpensItsDiffsAtThatFile() {
        val a = java.io.File.createTempFile("turn-a", ".txt").apply { writeText("2\n"); deleteOnExit() }
        val b = java.io.File.createTempFile("turn-b", ".txt").apply { writeText("2\n"); deleteOnExit() }
        val shown = mutableListOf<Pair<List<String>, Int>>()
        bridge.showTurnDiffs = { diffs, index -> shown += diffs.map { it.filePath } to index }
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"go"}""")
        for ((id, file) in listOf("t1" to a, "t2" to b)) connections[0].emit(
            Fx.toolUse("m1", id, "Edit", """{"file_path":${Fx.q(file.path)}}"""),
            Fx.toolResult(id, "ok", toolUseResultJson = Fx.editResult(file.path, "1\n")),
        )
        connections[0].emit(Fx.result())
        /** The files are added off the session lock, in a second update of the result item. */
        fun withFiles() = posted.lastOrNull { it.str("type") == "item" && it.obj("item")!!.arr("files") != null }?.obj("item")
        val deadline = System.currentTimeMillis() + 5000
        while (withFiles() == null && System.currentTimeMillis() < deadline) PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val result = withFiles()!!
        bridge.handle("""{"type":"openTurnDiff","id":${Fx.q(result.str("id")!!)},"index":1}""")
        bridge.handle("""{"type":"openTurnDiff","id":"result-unknown","index":0}""")
        assertEquals(listOf(listOf(a.path, b.path) to 1), shown)

        val all = mutableListOf<List<String>>()
        bridge.showTurnChanges = { diffs -> all += diffs.map { it.filePath } }
        bridge.handle("""{"type":"openTurnChanges","id":${Fx.q(result.str("id")!!)}}""")
        bridge.handle("""{"type":"openTurnChanges","id":"result-unknown"}""")
        assertEquals(listOf(listOf(a.path, b.path)), all)
    }

    fun testANewChatRecordsWhenItStarted() {
        val before = System.currentTimeMillis()
        val started = chats.open(ChatSpec.inProject(root)).startedAt!!
        assertTrue(started in before..System.currentTimeMillis())
    }

    fun testActivityIsPostedAndReplayedOnReady() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        val first = posted.last { it.str("type") == "activity" }
        assertEquals("starting", first.str("phase"))
        connections[0].emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/a"}"""))
        val running = posted.last { it.str("type") == "activity" }
        assertEquals("running", running.str("phase"))
        assertEquals("Read", running.str("tool"))
        connections[0].emit(Fx.result())
        assertTrue(posted.last { it.str("type") == "activity" }["phase"] is kotlinx.serialization.json.JsonNull)
        posted.clear()
        connections[0].emit(Fx.thinkingStart("m2", 0))
        assertTrue("no turn is running, and the session reports activity only while busy", posted.none { it.str("type") == "activity" })
    }

    fun testToolNameInTheStripIsCleanedAndShortened() {
        val msg = parseJsonObject(ChatBridge.activityMessage(tether.session.Activity.Running("mcp__x`\n" + "a".repeat(100))))!!
        val tool = msg.str("tool")!!
        assertTrue(tool.length <= 40)
        assertFalse(tool.contains('`'))
        assertFalse(tool.contains('\n'))
        assertEquals("tool", parseJsonObject(ChatBridge.activityMessage(tether.session.Activity.Running("  ")))!!.str("tool"))
    }

    fun testBackgroundWorkAloneHidesTheStrip() {
        val msg = parseJsonObject(ChatBridge.activityMessage(tether.session.Activity.Agents(2)))!!
        assertEquals("null", msg["phase"].toString())
    }

    fun testProcessesMessageCleansItsText() {
        val msg = parseJsonObject(ChatBridge.processesMessage(listOf(
            tether.session.BackgroundProcess("b1", "Bash", "npm run dev`\n" + "x".repeat(300), 1234),
        )))!!
        assertEquals("processes", msg.str("type"))
        val p = msg.arr("processes")!!.single() as kotlinx.serialization.json.JsonObject
        assertEquals("b1", p.str("id"))
        assertEquals("Bash", p.str("kind"))
        assertEquals("1234", p["startedAt"].toString())
        val description = p.str("description")!!
        assertTrue(description.length <= 200)
        assertFalse(description.contains('`'))
    }

    fun testUsageMessage() {
        val usage = tether.cli.ContextUsage(76000, 200000, 38, 83, listOf(
            tether.cli.ContextCategory("Messages", 52000),
            tether.cli.ContextCategory("Ski`lls\n" + "x".repeat(100), 10000),
            tether.cli.ContextCategory("  ", 5),
        ))
        val msg = parseJsonObject(ChatBridge.usageMessage(usage))!!
        assertEquals("usage", msg.str("type"))
        assertEquals("76000", msg["used"].toString())
        assertEquals("200000", msg["max"].toString())
        assertEquals("38", msg["percent"].toString())
        assertEquals("83", msg["autocompactAt"].toString())
        val cats = msg.arr("categories")!!.map { it.jsonObject }
        assertEquals("the blank name is dropped", 2, cats.size)
        assertEquals("Messages", cats[0].str("name"))
        val cleaned = cats[1].str("name")!!
        assertTrue(cleaned.length <= 40 && '`' !in cleaned && '\n' !in cleaned)
        assertFalse(parseJsonObject(ChatBridge.usageMessage(usage.copy(autocompactAt = null)))!!.containsKey("autocompactAt"))
    }

    fun testSetModeGoesToThisChat() {
        chat.start()
        chats.open(ChatSpec.inProject(root)).start()
        bridge.handle("""{"type":"setMode","mode":"plan"}""")
        assertEquals("set_permission_mode", connections[0].sentJson().last().obj("request")!!.str("subtype"))
        assertEquals("initialize", connections[1].sentJson().last().obj("request")!!.str("subtype"))
    }

    fun testAcpChatShowsTheAgentsModesAndAnswersWithItsOptions() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"setAgent","agent":"acp"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        val (command, conn) = acp.single()
        PlatformTestUtil.waitWithEventsDispatching("initialize was not sent", { conn.sent.isNotEmpty() }, 10)
        fun reply(method: String, result: String) {
            val id = conn.sentJson().last { it.str("method") == method }["id"]
            conn.emit("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        reply("initialize", """{"protocolVersion":1,"agentCapabilities":{}}""")
        reply("session/new", """{"sessionId":"s1","modes":{"currentModeId":"default","availableModes":[{"id":"default","name":"Default"},{"id":"accept_edits","name":"Accept edits"}]}}""")

        bridge.handle("""{"type":"setMode","mode":"accept_edits"}""")
        conn.emit("""{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"perm-check-1","title":"Run ls"},"options":[{"optionId":"allow_once","name":"Allow once","kind":"allow_once"}]}}""")
        val card = posted.last { it.str("type") == "item" }.obj("item")!!
        bridge.handle("""{"type":"answer","requestId":"${card.str("permissionRequestId")}","answers":{}}""")
        bridge.handle("""{"type":"permission","requestId":"${card.str("permissionRequestId")}","optionId":"allow_once"}""")

        assertEquals(listOf("hermes", "acp"), command)
        assertTrue(connections.isEmpty())
        assertEquals("main", chat.label)
        val modes = posted.last { it.str("type") == "modes" }.arr("modes")!!.map { it.jsonObject.str("id") }
        assertEquals(listOf("default", "accept_edits"), modes)
        assertEquals("default", posted.last { it.str("type") == "state" }.str("mode"))
        assertEquals("accept_edits", conn.sentJson().last { it.str("method") == "session/set_mode" }.obj("params")!!.str("modeId"))
        assertEquals(listOf("allow_once"), card.arr("choices")!!.map { it.jsonObject.str("id") })
        val answer = conn.sentJson().single { it.str("method") == null && it["id"].toString() == "0" }
        assertEquals("allow_once", answer.obj("result")!!.obj("outcome")!!.str("optionId"))
    }

    private fun lastAgent() = posted.last { it.str("type") == "agent" }

    fun testADraftShowsItsAgentsAndClaudesModesButNoSession() {
        bridge.handle("""{"type":"ready"}""")
        val agent = lastAgent()
        assertEquals(listOf("claude", "acp"), agent.arr("agents")!!.map { it.jsonObject.str("id") })
        assertEquals(listOf("Claude", "Hermes"), agent.arr("agents")!!.map { it.jsonObject.str("label") })
        assertEquals("claude", agent.str("selected"))
        assertEquals(false, agent.bool("locked"))
        val modes = posted.last { it.str("type") == "modes" }.arr("modes")!!.map { it.jsonObject.str("id") }
        assertEquals(listOf("default", "acceptEdits", "plan", "auto"), modes)
        assertFalse(posted.last { it.str("type") == "state" }.containsKey("sessionId"))
        assertNull(chat.session.sessionId)
        assertTrue(posted.none { it.str("type") == "usage" || it.str("type") == "activity" })
        assertTrue(connections.isEmpty())
    }

    fun testSwitchingTheDraftsAgentStartsNoProcessAndHidesClaudesModes() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"setAgent","agent":"acp"}""")
        assertEquals("acp", lastAgent().str("selected"))
        assertTrue(posted.last { it.str("type") == "modes" }.arr("modes")!!.isEmpty())
        bridge.handle("""{"type":"setAgent","agent":"claude"}""")
        assertEquals("claude", lastAgent().str("selected"))
        assertEquals(4, posted.last { it.str("type") == "modes" }.arr("modes")!!.size)
        assertTrue(connections.isEmpty())
        assertTrue(acp.isEmpty())
    }

    fun testTheFirstSendLocksTheAgent() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        bridge.handle("""{"type":"setAgent","agent":"acp"}""")
        assertEquals(true, lastAgent().bool("locked"))
        assertEquals("claude", lastAgent().str("selected"))
        assertEquals(1, connections.size)
        assertTrue(acp.isEmpty())
    }

    fun testAnAttachedImageSurvivesAnAgentChange() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.RED_PNG_BASE64)
        bridge.handle("""{"type":"setAgent","agent":"acp"}""")
        bridge.handle("""{"type":"setAgent","agent":"claude"}""")
        bridge.handle("""{"type":"send","text":"what color?","attachments":["p1"]}""")
        assertEquals(listOf(listOf(TestImages.RED_PNG_BASE64)), connections.single().userImages())
        assertTrue(acp.isEmpty())
    }

    fun testSetModelAndEffortGoToThisChat() {
        chat.start()
        bridge.handle("""{"type":"setModel","model":"sonnet"}""")
        assertEquals("set_model", connections[0].sentJson().last().obj("request")!!.str("subtype"))
        bridge.handle("""{"type":"setEffort","effort":"low"}""")
        assertEquals("apply_flag_settings", connections[0].sentJson().last().obj("request")!!.str("subtype"))
        bridge.handle("""{"type":"setModel"}""")
        bridge.handle("""{"type":"setEffort","effort":""}""")
        assertEquals("a missing or blank value sends nothing", "sonnet" to "low", chat.session.setup.model to chat.session.setup.effort)
    }

    fun testModelMessage() {
        val options = listOf(
            tether.cli.ModelOption("default", "claude-opus-5-5", "Default (recommended)", "Opus 5.5 · Best for everyday", listOf("low", "high")),
            tether.cli.ModelOption("x", "x", "Odd`name\n" + "y".repeat(100), null, emptyList()),
        )
        val msg = parseJsonObject(ChatBridge.modelMessage(tether.cli.ModelChoice(options, "default", "high")))!!
        assertEquals("model", msg.str("type"))
        assertEquals("default", msg.str("model"))
        assertEquals("Default (recommended)", msg.str("modelName"))
        assertEquals("high", msg.str("effort"))
        assertEquals(listOf("low", "high"), msg.arr("efforts")!!.map { (it as JsonPrimitive).content })
        val shown = msg.arr("options")!!.map { it.jsonObject }
        assertEquals(listOf("default", "x"), shown.map { it.str("value") })
        assertEquals("Default (recommended)", shown[0].str("name"))
        assertEquals("Opus 5.5 · Best for everyday", shown[0].str("description"))
        val cleaned = shown[1].str("name")!!
        assertTrue(cleaned.length <= 60 && '`' !in cleaned && '\n' !in cleaned)
        assertFalse(shown[1].containsKey("description"))
        assertFalse(parseJsonObject(ChatBridge.modelMessage(tether.cli.ModelChoice(options, "x", null)))!!.containsKey("effort"))
    }

    fun testCommandsMessageCleansAndCapsDescriptions() {
        val msg = parseJsonObject(ChatBridge.commandsMessage(listOf(
            tether.cli.SlashCommand("review", "Review a\npull request", "<pr>"),
            tether.cli.SlashCommand("long", "d".repeat(500), ""),
        )))!!
        assertEquals("commands", msg.str("type"))
        val items = msg.arr("items")!!.map { it.jsonObject }
        assertEquals(listOf("review", "long"), items.map { it.str("name") })
        assertEquals("Review a pull request", items[0].str("description"))
        assertEquals("<pr>", items[0].str("hint"))
        assertTrue(items[1].str("description")!!.length <= ChatBridge.MAX_COMMAND_TEXT)
    }

    fun testTheCommandsTheCliReportsReachThePage() {
        chat.start()
        bridge.handle("""{"type":"ready"}""")
        val conn = connections.single()
        conn.emit(Fx.initResponseWithCommands(conn.lastRequestId("initialize"), """[{"name":"simplify","description":"Simplify code","argumentHint":""}]"""))
        val shown = posted.last { it.str("type") == "commands" }
        assertEquals("simplify", shown.arr("items")!!.single().jsonObject.str("name"))
    }

    fun testANewChatOffersTheCommandsItsAgentLastReported() {
        chat.start()
        val conn = connections.single()
        conn.emit(Fx.initResponseWithCommands(conn.lastRequestId("initialize"), """[{"name":"simplify","description":"Simplify code","argumentHint":""}]"""))
        val fresh = chats.open(ChatSpec("wt-2", "$root/.claude/worktrees/wt-2"))
        val freshPosted = mutableListOf<JsonObject>()
        ChatBridge(project, fresh) { freshPosted += parseJsonObject(it)!! }.handle("""{"type":"ready"}""")
        assertEquals("simplify", freshPosted.last { it.str("type") == "commands" }.arr("items")!!.single().jsonObject.str("name"))
    }

    fun testNoCommandsMessageWhenNoneAreKnown() {
        bridge.handle("""{"type":"ready"}""")
        assertTrue(posted.none { it.str("type") == "commands" })
    }

    fun testFindFilesAnswersWithPathsTheChatCanMention() {
        myFixture.addFileToProject("src/webview/chat.js", "")
        bridge.handle("""{"type":"findFiles","reqId":7,"query":"chat.js"}""")
        val answer = filesAnswer()
        assertEquals(7L, answer.long("reqId"))
        val item = answer.arr("items")!!.single().jsonObject
        assertEquals("@src/webview/chat.js", item.str("mention"))
        assertEquals("chat.js", item.str("name"))
        assertEquals("src/webview", item.str("folder"))
    }

    fun testFindFilesAnswersEvenWithoutAQuery() {
        bridge.handle("""{"type":"findFiles","reqId":8}""")
        assertEquals(8L, filesAnswer().long("reqId"))
    }

    fun testANewerFileSearchIsTheOneAnswered() {
        myFixture.addFileToProject("src/webview/chat.js", "")
        bridge.handle("""{"type":"findFiles","reqId":1,"query":"c"}""")
        bridge.handle("""{"type":"findFiles","reqId":2,"query":"chat.js"}""")
        assertEquals(2L, filesAnswer().long("reqId"))
    }

    /** The page's answer to its file search, once the background search has finished. */
    private fun filesAnswer(): JsonObject {
        PlatformTestUtil.waitWithEventsDispatching("no files answer", { posted.any { it.str("type") == "files" } }, 10)
        return posted.last { it.str("type") == "files" }
    }

    fun testResolvePathsReturnsOnlyExistingFiles() {
        val file = myFixture.addFileToProject("src/x.txt", "a").virtualFile
        bridge.handle("""{"type":"resolvePaths","reqId":7,"candidates":["src/x.txt:1","nope.txt"]}""")
        val msg = posted.single()
        assertEquals("resolved", msg.str("type"))
        val paths = msg.obj("paths")!!
        assertEquals(file.url, paths.obj("src/x.txt:1")!!.str("target"))
        assertNull(paths["nope.txt"])
    }

    fun testUnknownMalformedAndRemovedMessagesAreIgnored() {
        chat.start()
        bridge.handle("""{"type":"nope"}""")
        bridge.handle("not json")
        bridge.handle("""{"type":"listSessions"}""")
        bridge.handle("""{"type":"resume","sessionId":"abc"}""")
        bridge.handle("""{"type":"newChat"}""")
        assertTrue(posted.isEmpty())
        assertEquals(1, connections.size)
    }

    fun testUnknownMalformedAndRemovedMessagesDoNotStartADraft() {
        bridge.handle("""{"type":"nope"}""")
        bridge.handle("not json")
        bridge.handle("""{"type":"listSessions"}""")
        bridge.handle("""{"type":"resume","sessionId":"abc"}""")
        bridge.handle("""{"type":"newChat"}""")
        assertTrue(posted.isEmpty())
        assertTrue(connections.isEmpty())
        assertTrue(acp.isEmpty())
    }

    fun testResolvePathsUsesTheChatsFolder() {
        val inWorktree = myFixture.addFileToProject(".claude/worktrees/wt1/lib/z.txt", "a").virtualFile
        val wtChat = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wtPosted = mutableListOf<JsonObject>()
        val wtBridge = ChatBridge(project, wtChat) { wtPosted += parseJsonObject(it)!! }
        wtBridge.pathsOffEdt = { it() }
        wtBridge.onEdt = { it() }
        wtBridge.handle("""{"type":"resolvePaths","reqId":1,"candidates":["lib/z.txt"]}""")
        assertEquals(inWorktree.url, wtPosted.single().obj("paths")!!.obj("lib/z.txt")!!.str("target"))
        bridge.handle("""{"type":"resolvePaths","reqId":2,"candidates":["lib/z.txt"]}""")
        assertNull(posted.single().obj("paths")!!["lib/z.txt"])
    }

    fun testSentTextIsRememberedAndBroadcastToOtherTabs() {
        val other = chats.open(ChatSpec.inProject(root))
        val otherPosted = mutableListOf<JsonObject>()
        val otherBridge = ChatBridge(project, other) { otherPosted += parseJsonObject(it)!! }
        try {
            otherBridge.handle("""{"type":"ready"}""")
            bridge.handle("""{"type":"send","text":"hello"}""")
            assertEquals(listOf("hello"), PromptHistory.getInstance(project).entries())
            val last = otherPosted.last { it.str("type") == "history" }
            assertEquals(listOf(JsonPrimitive("hello")), last.arr("entries")!!.toList())
        } finally {
            otherBridge.dispose()
        }
    }

    fun testSendToADeadChatIsNotRemembered() {
        chat.start()
        connections[0].exit(1, "boom")
        assertEquals(SessionState.DEAD, chat.session.state)
        bridge.handle("""{"type":"send","text":"ignored"}""")
        assertTrue(PromptHistory.getInstance(project).entries().isEmpty())
        assertTrue(connections[0].userMessages().isEmpty())
    }

    fun testCheckoutIsPostedAsText() {
        bridge.handle("""{"type":"ready"}""")
        chat.showCheckout("IDE: wt1")
        val msg = posted.last()
        assertEquals("checkout", msg.str("type"))
        assertEquals("IDE: wt1", msg.str("text"))
    }

    fun testReadyReplaysTheCheckout() {
        chat.showCheckout("IDE: main")
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "history", "reset", "agent", "modes", "state", "checkout"), types())
    }

    fun testDisposedBridgeStopsReceivingHistory() {
        bridge.handle("""{"type":"ready"}""")
        bridge.dispose()
        val before = posted.size
        PromptHistory.getInstance(project).add("later")
        assertEquals(before, posted.size)
    }

    fun testReadyPostsNoContextMessage() {
        myFixture.configureByText("calc.js", "function total(a) {\n  return a<caret> + 1;\n}\n")
        bridge.handle("""{"type":"ready"}""")
        assertTrue(posted.none { it.str("type") == "context" })
    }

    fun testContextOffMessageIsIgnoredAndTheNoteIsStillSent() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        val count = posted.size
        bridge.handle("""{"type":"contextOff","off":true}""")
        assertEquals(count, posted.size)
        bridge.handle("""{"type":"send","text":"hello"}""")
        PlatformTestUtil.waitWithEventsDispatching("Not sent", { connections[0].userMessages().isNotEmpty() }, 10)
        assertTrue(connections[0].userMessages().single().contains("[IDE] The user is looking at a.txt, caret at line 1:1."))
    }

    fun testSettingOffSendsNoNote() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        settings.attachEditorContext = false
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertEquals(listOf("hello"), connections[0].userMessages())
    }

    // --- attachments ---

    private fun upload(id: String, base64: String, name: String = "$id.png") {
        bridge.handle("""{"type":"attachStart","id":"$id","name":"$name","size":${base64.length / 4 * 3}}""")
        for (part in base64.chunked(64)) bridge.handle("""{"type":"attachChunk","id":"$id","data":"$part"}""")
        bridge.handle("""{"type":"attachEnd","id":"$id"}""")
    }

    private fun lastAttachment() = posted.last { it.str("type") == "attachment" }
    private fun notices() = posted.filter { it.str("type") == "item" }.map { it.obj("item")!! }.filter { it.str("kind") == "notice" }.map { it.str("text") }

    fun testUploadedImageIsShownAndSentWithTheMessage() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.RED_PNG_BASE64)
        val shown = lastAttachment()
        assertEquals("p1", shown.str("id"))
        assertEquals("p1.png", shown.str("name"))
        assertEquals("data:image/png;base64,${TestImages.RED_PNG_BASE64}", shown.str("thumb"))
        bridge.handle("""{"type":"send","text":"what color?","attachments":["p1"]}""")
        assertTrue(connections[0].userMessages().single().endsWith("what color?"))
        assertEquals(listOf(listOf(TestImages.RED_PNG_BASE64)), connections[0].userImages())
        val bubble = posted.last { it.str("type") == "item" && it.obj("item")!!.str("kind") == "user" }.obj("item")!!
        assertEquals(listOf(JsonPrimitive("data:image/png;base64,${TestImages.RED_PNG_BASE64}")), bubble.arr("images")!!.toList())
        assertEquals(0, chat.attachments.size)
        assertEquals(listOf("what color?"), PromptHistory.getInstance(project).entries())
    }

    private fun openImage(fields: String) = bridge.handle("""{"type":"openImage",$fields}""")

    private fun allowImageFiles() = VfsRootAccess.allowRootAccess(testRootDisposable, ImageFiles.DEFAULT_PARENT.toString())

    private fun binaryInProject(path: String, bytes: ByteArray): VirtualFile =
        myFixture.addFileToProject(path, "").virtualFile.also { file -> WriteAction.runAndWait<IOException> { file.setBinaryContent(bytes) } }

    fun testClickingASentImageOpensTheBytesClaudeGot() {
        allowImageFiles()
        chat.start()
        bridge.handle("""{"type":"ready"}""")
        upload("p1", Base64.getEncoder().encodeToString(TestImages.solid(1200, 600)), name = "Screenshot.png")
        bridge.handle("""{"type":"send","text":"look","attachments":["p1"]}""")
        val bubble = posted.last { it.str("type") == "item" && it.obj("item")!!.str("kind") == "user" }.obj("item")!!
        openImage(""""item":${Fx.q(bubble.str("id")!!)},"index":0""")
        openImage(""""item":${Fx.q(bubble.str("id")!!)},"index":1""")
        val file = opened.single()
        assertEquals("Screenshot.png", file.name)
        assertEquals(connections[0].userImages().single().single(), Base64.getEncoder().encodeToString(file.contentsToByteArray()))
    }

    fun testClickingAReplyImageOpensTheFileInTheProject() {
        val file = binaryInProject("docs/blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        openImage(""""path":"docs/blue.png"""")
        assertEquals(listOf(file), opened)
    }

    fun testReplyImagePathsThatLeaveTheProjectOpenNothing() {
        binaryInProject("docs/blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        openImage(""""path":"docs/../docs/blue.png"""")
        openImage(""""path":"https://example.com/blue.png"""")
        openImage(""""path":"docs/missing.png"""")
        assertTrue(opened.isEmpty())
    }

    fun testClickingAToolImageOpensTheFileTheToolRead() {
        val file = binaryInProject("docs/blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        chat.start()
        bridge.handle("""{"type":"send","text":"read it"}""")
        connections[0].emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"docs/blue.png"}"""), Fx.readImageResult("t1", TestImages.BLUE_PNG_BASE64))
        openImage(""""tool":"t1","index":0""")
        assertEquals(listOf(file), opened)
    }

    fun testAToolImageFromOutsideTheProjectOpensACopyNotTheFile() {
        allowImageFiles()
        val dir = createTempDirectory("tool-read").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val png = File(dir, "secret.png").apply { writeBytes(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        chat.start()
        bridge.handle("""{"type":"send","text":"read it"}""")
        connections[0].emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":${Fx.q(png.path)}}"""), Fx.readImageResult("t1", TestImages.BLUE_PNG_BASE64))
        openImage(""""tool":"t1","index":0""")
        val shown = opened.single()
        assertFalse(shown.path == png.path)
        assertEquals("image.png", shown.name)
    }

    fun testAToolImageWithoutAFileOpensACopyOnceWrittenOnce() {
        allowImageFiles()
        chat.start()
        bridge.handle("""{"type":"send","text":"take a screenshot"}""")
        connections[0].emit(Fx.toolUse("m1", "t1", "mcp__shot__take", "{}"), Fx.readImageResult("t1", TestImages.BLUE_PNG_BASE64))
        openImage(""""tool":"t1","index":0""")
        openImage(""""tool":"t1","index":0""")
        openImage(""""tool":"t1","index":1""")
        assertEquals(2, opened.size)
        assertEquals(opened[0], opened[1])
        assertEquals(TestImages.BLUE_PNG_BASE64, Base64.getEncoder().encodeToString(opened[0].contentsToByteArray()))
    }

    fun testImageOnlyMessageIsSentButNotRemembered() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"send","text":"","attachments":["p1"]}""")
        assertEquals(listOf(listOf(TestImages.BLUE_PNG_BASE64)), connections[0].userImages())
        assertTrue(PromptHistory.getInstance(project).entries().isEmpty())
    }

    fun testBlankMessageWithUnknownAttachmentsIsIgnored() {
        bridge.handle("""{"type":"send","text":"  ","attachments":["nope", 3]}""")
        assertTrue(connections.isEmpty())
    }

    fun testBlankMessageWithUnknownAttachmentsIsIgnoredByAStartedChat() {
        chat.start()
        bridge.handle("""{"type":"send","text":"  ","attachments":["nope", 3]}""")
        assertTrue(connections[0].userMessages().isEmpty())
    }

    fun testMagicBytesDecideTheType() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", Base64.getEncoder().encodeToString(TestImages.solid(8, 8, "jpeg")), name = "looks-like.png")
        assertTrue(lastAttachment().str("thumb")!!.startsWith("data:image/jpeg;base64,"))
    }

    fun testNonImageIsRefusedWithANotice() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", Base64.getEncoder().encodeToString("hello world!".toByteArray()), name = "notes.txt")
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals("Couldn't attach notes.txt: not a PNG, JPEG, GIF, WebP or HEIC image.", notices().last())
        assertEquals(0, chat.attachments.size)
    }

    fun testFileOver20MbIsRefusedBeforeItsBytesArrive() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"attachStart","id":"p1","name":"huge.png","size":20971521}""")
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals("Couldn't attach huge.png: it is larger than 20 MB.", notices().last())
        bridge.handle("""{"type":"attachChunk","id":"p1","data":"${TestImages.BLUE_PNG_BASE64}"}""")
        bridge.handle("""{"type":"attachEnd","id":"p1"}""")
        assertEquals(0, chat.attachments.size)
    }

    fun testEleventhImageIsRefused() {
        bridge.handle("""{"type":"ready"}""")
        for (i in 1..10) upload("p$i", TestImages.BLUE_PNG_BASE64)
        assertEquals(10, chat.attachments.size)
        upload("p11", TestImages.BLUE_PNG_BASE64)
        assertEquals("p11", lastAttachment().str("id"))
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals("Couldn't attach p11.png: at most 10 images per message.", notices().last())
        assertEquals(10, chat.attachments.size)
    }

    fun testRemovedAttachmentIsNotSent() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"detach","id":"p1"}""")
        bridge.handle("""{"type":"send","text":"x","attachments":["p1"]}""")
        assertEquals(listOf(emptyList<String>()), connections[0].userImages())
    }

    fun testAttachmentRemovedWhilePreparingIsDropped() {
        val tasks = mutableListOf<() -> Unit>()
        bridge.offEdt = { tasks += it }
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"detach","id":"p1"}""")
        tasks.forEach { it() }
        assertTrue(posted.none { it.str("type") == "attachment" })
        assertEquals(0, chat.attachments.size)
    }

    fun testReloadedPageGetsTheUnsentTextAndAttachmentsBack() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"unsentText","text":"half a thought"}""")
        posted.clear()
        bridge.handle("""{"type":"ready"}""")
        assertEquals(1, chat.attachments.size)
        assertEquals("p1", lastAttachment().str("id"))
        assertEquals("half a thought", posted.last { it.str("type") == "unsentText" }.str("text"))
    }

    fun testReloadedPageDropsUnfinishedUploads() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"attachStart","id":"p1","name":"p1.png","size":100}""")
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"attachEnd","id":"p1"}""")
        assertEquals(0, chat.attachments.size)
    }

    fun testNoUnsentTextIsPostedWhenThereIsNone() {
        bridge.handle("""{"type":"ready"}""")
        assertTrue(posted.none { it.str("type") == "unsentText" })
    }

    fun testSendingClearsTheUnsentText() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"unsentText","text":"hello"}""")
        bridge.handle("""{"type":"send","text":"hello","attachments":[]}""")
        assertEquals("", chat.unsentText)
    }

    fun testOversizeUnsentTextKeepsNothing() {
        bridge.handle("""{"type":"unsentText","text":"shorter, earlier"}""")
        bridge.handle("""{"type":"unsentText","text":"${"x".repeat(ChatBridge.MAX_UNSENT_CHARS + 1)}"}""")
        assertEquals("", chat.unsentText)
    }

    fun testAReplyWithoutCommandsKeepsTheOnesOtherChatsOffer() {
        chat.start()
        val conn = connections.single()
        conn.emit(Fx.initResponseWithCommands(conn.lastRequestId("initialize"), """[{"name":"simplify"}]"""))
        chats.open(ChatSpec("wt-3", "$root/.claude/worktrees/wt-3")).start()
        connections[1].emit(Fx.initResponse(connections[1].lastRequestId("initialize")))
        assertEquals(listOf("simplify"), chats.commandCatalog.commandsOf(tether.session.Agent.CLAUDE).map { it.name })
    }

    fun testARestoredChatStartsWithItsUnsentText() {
        val restored = chats.open(ChatSpec.inProject(root).copy(unsentText = "left over"))
        assertEquals("left over", restored.unsentText)
    }

    fun testPickedFilesBecomeAttachments() {
        val file = myFixture.addFileToProject("pics/blue.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        bridge.chooseImages = { done -> done(listOf(file)) }
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"pickImages"}""")
        val shown = lastAttachment()
        assertEquals("k1", shown.str("id"))
        assertEquals("blue.png", shown.str("name"))
        assertEquals("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}", shown.str("thumb"))
        assertEquals(1, chat.attachments.size)
    }

    private fun droppedFile(name: String, bytes: ByteArray): File =
        File(createTempDirectory().toFile(), name).apply { writeBytes(bytes) }

    fun testDroppedFilesBecomeAttachments() {
        val file = droppedFile("blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        bridge.handle("""{"type":"ready"}""")
        bridge.attachDropped(listOf(file))
        val attachments = posted.filter { it.str("type") == "attachment" }
        assertEquals(true, attachments.first().bool("loading"))
        val shown = attachments.last()
        assertEquals("k1", shown.str("id"))
        assertEquals("blue.png", shown.str("name"))
        assertEquals("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}", shown.str("thumb"))
        assertEquals(1, chat.attachments.size)
    }

    fun testDroppedNonImagesAndFoldersAreRefusedWithANotice() {
        val text = droppedFile("notes.txt", "hello".toByteArray())
        val folder = createTempDirectory().toFile()
        bridge.handle("""{"type":"ready"}""")
        bridge.attachDropped(listOf(text, folder))
        assertEquals(0, chat.attachments.size)
        assertEquals(1, posted.count { it.str("type") == "attachment" && it.bool("failed") == true })
        assertTrue("a folder gets no tile", posted.none { it.str("type") == "attachment" && it.str("name") == folder.name })
        assertEquals(listOf("notes.txt", folder.name), notices().map { it!!.substringAfter("Couldn't attach ").substringBefore(":") })
    }

    fun testDropsOnADeadChatAreIgnored() {
        chat.start()
        connections[0].exit(1, "boom")
        bridge.handle("""{"type":"ready"}""")
        posted.clear()
        bridge.attachDropped(listOf(droppedFile("blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))))
        assertFalse(bridge.acceptsDrops())
        assertTrue(posted.none { it.str("type") == "attachment" })
        assertEquals(0, chat.attachments.size)
    }

    fun testDragHoverTellsThePageWhereFilesWillLand() {
        bridge.dropHover(true)
        bridge.dropHover(false)
        val hovers = posted.filter { it.str("type") == "dropTarget" }
        assertEquals(listOf(true, false), hovers.map { it.bool("active") })
    }

    fun testPickedFileShowsALoadingTileBeforeItsThumbnail() {
        val file = myFixture.addFileToProject("pics/blue.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        bridge.chooseImages = { done -> done(listOf(file)) }
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"pickImages"}""")
        val attachments = posted.filter { it.str("type") == "attachment" }
        assertEquals(2, attachments.size)
        assertEquals("k1", attachments[0].str("id"))
        assertEquals("blue.png", attachments[0].str("name"))
        assertEquals(true, attachments[0].bool("loading"))
        assertNull(attachments[1].bool("loading"))
        assertNotNull(attachments[1].str("thumb"))
    }

    fun testPreparerThatThrowsStillRemovesTheTileAndNotifies() {
        bridge.preparer = { error("boom") }
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals(0, chat.attachments.size)
        assertTrue(notices().single()!!.contains("Couldn't attach"))
    }

    fun testOutOfMemoryWhilePreparingIsRefusedAsTooLarge() {
        bridge.preparer = { throw OutOfMemoryError("boom") }
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        assertEquals(true, lastAttachment().bool("failed"))
        assertTrue(notices().single()!!.contains("too large to process"))
    }

    // --- large histories ---

    fun testBatchesKeepOrderAndStayUnderTheLimit() {
        val thirtyCharacterItems = (1..5).map { buildJsonObject { put("id", "i$it"); put("pad", "xxxxxxxxxx") } }
        assertEquals(listOf(listOf("i1", "i2"), listOf("i3", "i4"), listOf("i5")), ChatBridge.batches(thirtyCharacterItems, 70).map { b -> b.map { it.str("id") } })
        assertEquals("an item over the limit goes alone", 5, ChatBridge.batches(thirtyCharacterItems, 20).size)
        assertEquals(1, ChatBridge.batches(thirtyCharacterItems, 1_000).size)
        assertTrue(ChatBridge.batches(emptyList(), 70).isEmpty())
    }

    fun testEmptyChatStillGetsOneReset() {
        bridge.handle("""{"type":"ready"}""")
        val reset = posted.single { it.str("type") == "reset" }
        assertTrue(reset.arr("items")!!.isEmpty())
        assertTrue(posted.none { it.str("type") == "items" })
    }

    // --- local images in replies ---

    fun testResolveImagesAnswersWithDataUrlsForLocalImagesOnly() {
        val file = myFixture.addFileToProject("docs/blue.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        bridge.handle("""{"type":"resolveImages","reqId":4,"paths":["docs/blue.png","docs/missing.png","https://x/y.png",7]}""")
        val msg = posted.single()
        assertEquals("images", msg.str("type"))
        assertEquals(4L, msg["reqId"].toString().toLong())
        val images = msg.obj("images")!!
        assertEquals(setOf("docs/blue.png"), images.keys)
        assertEquals("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}", images.str("docs/blue.png"))
    }

    fun testResolveImagesUsesTheChatsFolder() {
        val file = myFixture.addFileToProject(".claude/worktrees/wt1/img/w.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        val wtChat = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wtPosted = mutableListOf<JsonObject>()
        val wtBridge = ChatBridge(project, wtChat) { wtPosted += parseJsonObject(it)!! }
        wtBridge.offEdt = { it() }
        wtBridge.onEdt = { it() }
        wtBridge.handle("""{"type":"resolveImages","reqId":1,"paths":["img/w.png"]}""")
        assertNotNull(wtPosted.single().obj("images")!!.str("img/w.png"))
        bridge.handle("""{"type":"resolveImages","reqId":2,"paths":["img/w.png"]}""")
        assertTrue(posted.single().obj("images")!!.isEmpty())
    }

    fun testResolveImagesAnswersTheOthersWhenOnePathThrows() {
        bridge.localImage = { path, _ -> if (path == "bad.png") error("boom") else "data:image/png;base64,AAAA" }
        bridge.handle("""{"type":"resolveImages","reqId":9,"paths":["a.png","bad.png","b.png"]}""")
        assertEquals(setOf("a.png", "b.png"), posted.single().obj("images")!!.keys)
    }

    fun testResolveImagesAnswersEvenWhenEveryPathThrows() {
        bridge.localImage = { _, _ -> throw OutOfMemoryError("boom") }
        bridge.handle("""{"type":"resolveImages","reqId":9,"paths":["a.png"]}""")
        assertTrue(posted.single().obj("images")!!.isEmpty())
    }

    fun testResolveImagesRunsOffTheEdt() {
        val tasks = mutableListOf<() -> Unit>()
        bridge.offEdt = { tasks += it }
        bridge.handle("""{"type":"resolveImages","reqId":1,"paths":["a.png"]}""")
        assertTrue(posted.isEmpty())
        assertEquals(1, tasks.size)
    }

    // --- answering Claude's questions ---

    private fun askTwo() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"ask"}""")
        connections[0].emit(Fx.toolUse("m1", "tq", "AskUserQuestion", Fx.TWO_QUESTIONS), Fx.askQuestion("q1", "tq", Fx.TWO_QUESTIONS))
    }

    private fun answerMsg(requestId: String, answers: String) = """{"type":"answer","requestId":"$requestId","answers":$answers}"""
    private val validAnswers = """{"Which colour?":{"labels":["Red"]},"Which extras?":{"labels":["A, B","C"],"other":"D"}}"""
    private fun responses() = connections[0].sentJson().filter { it.str("type") == "control_response" }

    fun testQuestionItemReachesThePage() {
        askTwo()
        val item = posted.last { it.str("type") == "item" }.obj("item")!!
        assertEquals("awaiting_permission", item.str("status"))
        val q = item.obj("question")!!.arr("questions")!!
        assertEquals(2, q.size)
        assertEquals("Extras", q[1].jsonObject.str("header"))
        assertEquals(true, q[1].jsonObject.bool("multiSelect"))
    }

    fun testValidAnswerIsSentAsAllow() {
        askTwo()
        bridge.handle(answerMsg("q1", validAnswers))
        val resp = responses().single().obj("response")!!.obj("response")!!
        assertEquals("allow", resp.str("behavior"))
        val answers = resp.obj("updatedInput")!!.obj("answers")!!
        assertEquals("Red", answers.str("Which colour?"))
        assertEquals("A, B, C, D", answers.str("Which extras?"))
        assertEquals("running", posted.last { it.str("type") == "item" }.obj("item")!!.str("status"))
    }

    fun testFreeTextForSingleSelectIsAccepted() {
        askTwo()
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":[],"other":"Teal"},"Which extras?":{"labels":["C"]}}"""))
        assertEquals("Teal", responses().single().obj("response")!!.obj("response")!!.obj("updatedInput")!!.obj("answers")!!.str("Which colour?"))
    }

    private fun assertRejected(message: String) {
        askTwo()
        val before = connections[0].sent.size
        bridge.handle(message)
        assertEquals(before, connections[0].sent.size)
        assertEquals("awaiting_permission", posted.last { it.str("type") == "item" }.obj("item")!!.str("status"))
    }

    fun testAnswerForUnknownRequestIsRejected() = assertRejected(answerMsg("zzz", validAnswers))

    fun testAnswerWithWrongQuestionKeysIsRejected() {
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Red"]},"Something else?":{"labels":["C"]}}"""))
    }

    fun testAnswerMissingAQuestionIsRejected() = assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Red"]}}"""))

    fun testAnswerWithLabelNotInOptionsIsRejected() {
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Purple"]},"Which extras?":{"labels":["C"]}}"""))
    }

    fun testSingleSelectWithTwoLabelsIsRejected() {
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Red","Blue"]},"Which extras?":{"labels":["C"]}}"""))
    }

    fun testOversizeFreeTextIsRejected() {
        val big = "x".repeat(2001)
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":[],"other":"$big"},"Which extras?":{"labels":["C"]}}"""))
    }

    fun testMalformedAnswerShapesAreRejected() {
        askTwo()
        val before = connections[0].sent.size
        bridge.handle("""{"type":"answer","requestId":"q1"}""")
        bridge.handle(answerMsg("q1", "[]"))
        bridge.handle(answerMsg("q1", """{"Which colour?":"Red","Which extras?":"C"}"""))
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":"Red"},"Which extras?":{"labels":["C"]}}"""))
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":[1]},"Which extras?":{"labels":["C"]}}"""))
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":["Red"],"other":5},"Which extras?":{"labels":["C"]}}"""))
        assertEquals(before, connections[0].sent.size)
    }

    fun testMalformedAnswerShapeReemitsPendingQuestion() {
        askTwo()
        bridge.handle("""{"type":"answer","requestId":"q1","answers":[]}""")
        assertTrue(posted.any { it.obj("item")?.str("kind") == "notice" })
        assertEquals("awaiting_permission", posted.last { it.str("type") == "item" }.obj("item")!!.str("status"))
    }

    fun testAnswerForAnOrdinaryPermissionRequestIsRejected() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"x"}""")
        val input = """{"file_path":"/p/a.txt","content":"hi"}"""
        connections[0].emit(Fx.toolUse("m1", "t1", "Write", input), Fx.canUseTool("r1", "t1", "Write", input))
        bridge.handle(answerMsg("r1", "{}"))
        assertTrue(responses().isEmpty())
    }

    fun testSkipDeniesTheQuestion() {
        askTwo()
        bridge.handle("""{"type":"skipQuestion","requestId":"q1"}""")
        val resp = responses().single().obj("response")!!.obj("response")!!
        assertEquals("deny", resp.str("behavior"))
        assertEquals("The user skipped the question.", resp.str("message"))
    }

    fun testSkipOfAnOrdinaryPermissionRequestIsIgnored() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"x"}""")
        val input = """{"file_path":"/p/a.txt","content":"hi"}"""
        connections[0].emit(Fx.toolUse("m1", "t1", "Write", input), Fx.canUseTool("r1", "t1", "Write", input))
        bridge.handle("""{"type":"skipQuestion","requestId":"r1"}""")
        assertTrue(responses().isEmpty())
    }

    fun testPermissionAllowCannotAnswerAQuestion() {
        askTwo()
        bridge.handle("""{"type":"permission","requestId":"q1","decision":"allow"}""")
        bridge.handle("""{"type":"permission","requestId":"q1","decision":"allowSession"}""")
        assertTrue(responses().isEmpty())
    }
}
