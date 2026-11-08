package tether.session

import tether.cli.CliConnection
import tether.cli.arr
import tether.cli.obj
import tether.cli.str
import kotlinx.serialization.json.jsonObject
import tether.cli.PermissionMode
import tether.cli.SessionLaunch
import tether.context.EditorContextService
import tether.context.Symbol
import tether.git.GitWorktrees
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tether.settings.TetherSettings
import tether.ui.NewChatPopup
import tether.ide.GitTracker
import tether.testutil.FakeConnection
import tether.testutil.git
import tether.testutil.gitAvailable
import tether.testutil.Fx
import tether.testutil.TestImages
import tether.image.Attachment
import tether.image.Image
import tether.image.ImageType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class ChatsTest : BasePlatformTestCase() {
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private val acpLaunches = mutableListOf<Pair<List<String>, FakeConnection>>()
    private lateinit var home: File
    private val chats get() = Chats.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    private class RecordingView : SessionView {
        val resets = mutableListOf<List<ChatItem>>()
        val items = mutableListOf<ChatItem>()
        val states = mutableListOf<SessionState>()
        val inserted = mutableListOf<String>()
        override fun reset(items: List<ChatItem>) { resets += items }
        override fun item(item: ChatItem) { items += item }
        override fun state(state: SessionState, mode: String?) { states += state }
        override fun insert(text: String) { inserted += text }
        val activities = mutableListOf<Activity?>()
        override fun activity(activity: Activity?) { activities += activity }
        val suggestions = mutableListOf<String>()
        override fun suggestion(text: String) { suggestions += text }
        val queues = mutableListOf<List<QueuedMessage>>()
        override fun queue(messages: List<QueuedMessage>) { queues += messages }
    }

    private val settings get() = TetherSettings.getInstance().state
    private val contextService get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private var savedAttach = true
    private lateinit var savedProjectDir: String
    private lateinit var savedFinder: (PsiFile, Document, Int) -> Pair<Symbol?, Symbol?>
    private var savedTimeout = 0L
    private var savedAcpCommand: String? = null
    private var savedLastAgent: String? = null
    private lateinit var savedAcpConnector: (List<String>, String) -> CliConnection

    /** Lets the background symbol lookup finish and the send complete on the EDT. */
    private fun awaitMessages(connection: FakeConnection, count: Int) =
        PlatformTestUtil.waitWithEventsDispatching("Expected $count messages, got ${connection.userMessages()}", { connection.userMessages().size >= count }, 10)

    /**
     * The light project and its services are shared between tests: closes the chats and every editor (a leftover
     * one would add editor context to every message), and saves all documents, since the chats opened by a test
     * only hear about later saves.
     */
    override fun setUp() {
        super.setUp()
        chats.closeAll()
        manager.openFiles.forEach(manager::closeFile)
        savedAttach = settings.attachEditorContext
        settings.attachEditorContext = true
        savedProjectDir = contextService.projectDir
        contextService.projectDir = root
        savedFinder = contextService.finder
        savedTimeout = contextService.lookupTimeoutMs
        contextService.forgetCache()
        home = Files.createTempDirectory("claude-home").toFile()
        chats.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        savedAcpConnector = chats.acpConnector
        chats.acpConnector = { command, _ -> FakeConnection().also { acpLaunches += command to it } }
        savedAcpCommand = settings.acpCommand
        settings.acpCommand = "hermes acp"
        savedLastAgent = settings.lastAgent
        settings.lastAgent = ""
        chats.store = SessionStore(home)
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    override fun tearDown() {
        try {
            chats.closeAll()
            home.deleteRecursively()
            manager.openFiles.forEach(manager::closeFile)
            settings.attachEditorContext = savedAttach
            contextService.projectDir = savedProjectDir
            contextService.finder = savedFinder
            contextService.lookupTimeoutMs = savedTimeout
            contextService.forgetCache()
            chats.acpConnector = savedAcpConnector
            settings.acpCommand = savedAcpCommand
            settings.lastAgent = savedLastAgent
        } finally {
            super.tearDown()
        }
    }

    private val openChats get() = OpenChats.getInstance(project)

    private fun completeAcp(connection: FakeConnection, method: String, result: String) {
        PlatformTestUtil.waitWithEventsDispatching("$method was not sent", { connection.sentJson().any { it.str("method") == method } }, 10)
        val id = connection.sentJson().last { it.str("method") == method }["id"]
        connection.emit("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    /** Reads the native resume popup's current tab inventory without displaying a popup. */
    private fun nativeResumeSessions(): Any? = NewChatPopup::class.java
        .getDeclaredMethod("openSessions", Chats::class.java)
        .apply { isAccessible = true }
        .invoke(NewChatPopup, chats)

    fun testOpenChatsWithASessionAreRememberedOnceRestoreHasRun() {
        openChats.loadState(OpenChats.State())
        val a = chats.open(ChatSpec.inProject(root)).also { it.start() }
        connections[0].emit(Fx.init(sessionId = "s-a", cwd = root))
        assertEquals("before restore, the saved list is kept as loaded", emptyList<String?>(), openChats.state.chats.map { it.sessionId })
        assertEquals(emptyList<Pair<ChatSpec, Transcript>>(), chats.restorable())
        assertEquals("restoring alone does not start following the chats", listOf<String?>(), openChats.state.chats.map { it.sessionId })
        openChats.startTracking()
        chats.open(ChatSpec.inProject(root)).start()
        val b = chats.open(ChatSpec("wt-1", "$root/wt")).also { it.start() }
        connections[2].emit(Fx.init(sessionId = "s-b", cwd = "$root/wt"))
        assertEquals("a chat without a message has no session, so nothing to resume",
            listOf("s-a" to root, "s-b" to "$root/wt"), openChats.state.chats.map { it.sessionId to it.cwd })
        chats.close(a)
        assertEquals(listOf("s-b"), openChats.state.chats.map { it.sessionId })
        chats.close(b)
    }

    fun testRestorableSpecsResumeSavedSessionsThatStillExist() {
        val dir = Files.createTempDirectory("restore").toFile()
        val main = dir.path
        val wt = File(dir, "wt").apply { mkdirs() }
        try {
            fun transcript(cwd: String, id: String, extra: String = "") = File(chats.store.projectDir(cwd).apply { mkdirs() }, "$id.jsonl")
                .writeText("""{"type":"user","message":{"role":"user","content":"hi $id"}}""" + "\n" + extra)
            transcript(main, "kept")
            transcript(wt.path, "named", """{"type":"custom-title","customTitle":"Login fix","sessionId":"named"}""")
            transcript(main, "archived", """{"type":"custom-title","customTitle":"[archived] old","sessionId":"archived"}""")
            openChats.loadState(OpenChats.State().apply {
                chats = listOf("kept" to main, "named" to wt.path, "archived" to main, "no-transcript" to main, "gone" to "/no/such/folder")
                    .map { (id, cwd) -> OpenChats.Entry().apply { sessionId = id; this.cwd = cwd } }.toMutableList()
                chats[0].apply { mode = "plan"; model = "sonnet"; effort = "low" }
                chats[1].apply { mode = "not-a-mode" }
            })
            val specs = chats.restorable().map { it.first }
            assertEquals(listOf("kept", "named"), specs.map { it.resumeId })
            assertEquals(listOf(main, wt.path), specs.map { it.cwd })
            assertEquals("Login fix", specs[1].title)
            assertEquals(SessionSetup(PermissionMode.PLAN, "sonnet", "low"), specs[0].setup)
            assertEquals("an unknown mode is dropped, and nothing saved is nothing chosen", SessionSetup(), specs[1].setup)
        } finally { dir.deleteRecursively() }
    }

    fun testEachChatsModeModelAndEffortAreRemembered() {
        openChats.loadState(OpenChats.State())
        openChats.startTracking()
        val a = chats.open(ChatSpec.inProject(root)).also { it.start() }
        connections[0].emit(Fx.init(sessionId = "s-a", cwd = root))
        a.session.setMode("plan")
        a.session.setModel("sonnet")
        a.session.setEffort("low")
        val saved = openChats.state.chats.single()
        assertEquals(listOf("s-a", "plan", "sonnet", "low"), listOf(saved.sessionId, saved.mode, saved.model, saved.effort))
        chats.close(a)
    }

    fun testAcpProviderAndLocalTitleAreRemembered() {
        openChats.loadState(OpenChats.State())
        openChats.startTracking()
        val chat = chats.open(ChatSpec.inProject(root))
        assertTrue(chat.selectAgent("acp"))
        chat.rename("Hermes task")
        chat.start()
        val connection = acpLaunches.single().second
        completeAcp(connection, "initialize", """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
        completeAcp(connection, "session/new", """{"sessionId":"saved-acp"}""")
        chat.rename("Hermes task 2")

        val saved = openChats.state.chats.single()
        assertEquals(listOf("saved-acp", root, "acp", "Hermes task 2"), listOf(saved.sessionId, saved.cwd, saved.agentId, saved.title))
        assertEquals("acp", openChats.saved().single().agentId)
        assertEquals("Hermes task 2", openChats.saved().single().title)
        assertFalse(File(chats.store.projectDir(root), "saved-acp.jsonl").exists())
        assertTrue(launches.isEmpty())
    }

    fun testRestoredAcpSessionLoadsWithItsProviderAndLocalTitle() {
        val folder = File(home, "restored").apply { mkdirs() }.path
        assertTrue(File(folder).isDirectory)
        openChats.loadState(OpenChats.State().apply {
            chats = mutableListOf(OpenChats.Entry().apply {
                sessionId = "saved-acp"
                cwd = folder
                agentId = "acp"
                title = "Hermes task"
                mode = "yolo"
            })
        })
        settings.lastAgent = "claude"
        val (spec, transcript) = chats.restorable().single()
        assertEquals("acp", spec.agentId)
        assertEquals("saved-acp", spec.resumeId)
        assertEquals(folder, spec.cwd)
        assertEquals("Hermes task", spec.title)
        assertEquals("yolo", spec.agentMode)
        assertEquals(SessionSetup(), openChats.saved().single().setup)
        assertTrue(transcript.items.isEmpty())
        assertNull(chats.read(spec))

        val chat = chats.open(spec, transcript)
        assertTrue(chat.started)
        assertEquals("acp", chat.agent.id)
        assertEquals("Hermes task", chat.title)
        assertFalse(chat.selectAgent("claude"))
        val (command, connection) = acpLaunches.single()
        completeAcp(connection, "initialize", """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
        val load = connection.sentJson().single { it.str("method") == "session/load" }.obj("params")!!
        assertEquals("saved-acp", load.str("sessionId"))
        assertEquals(folder, load.str("cwd"))
        connection.emit("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"saved-acp","update":{"sessionUpdate":"user_message_chunk","messageId":"old-user","content":{"type":"text","text":"earlier question"}}}}""")
        connection.emit("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"saved-acp","update":{"sessionUpdate":"agent_message_chunk","messageId":"old-answer","content":{"type":"text","text":"earlier answer"}}}}""")
        completeAcp(connection, "session/load", "{}")

        val history = RecordingView().also(chat::attach).resets.single()
        val user = history.filterIsInstance<ChatItem.UserText>().single()
        val assistant = history.filterIsInstance<ChatItem.AssistantText>().single()
        assertEquals("earlier question", user.text)
        assertFalse(user.revertable)
        assertEquals("earlier answer", assistant.text)
        assertFalse(assistant.streaming)
        assertEquals(listOf("hermes", "acp"), command)
        assertFalse(connection.sentJson().any { it.str("method") in listOf("session/new", "session/prompt") })
        assertTrue(launches.isEmpty())
    }

    fun testAcpRestoreDoesNotReadASameIdClaudeTranscript() {
        val folder = File(home, "restored").apply { mkdirs() }.path
        assertTrue(File(folder).isDirectory)
        pastSession("shared-id", "Claude history", folder)
        chats.store.saveTitle(folder, "shared-id", "Claude title")
        openChats.loadState(OpenChats.State().apply {
            chats = mutableListOf(OpenChats.Entry().apply {
                sessionId = "shared-id"
                cwd = folder
                agentId = "acp"
                title = "Hermes title"
            })
        })

        val (spec, transcript) = chats.restorable().single()
        assertEquals("acp", spec.agentId)
        assertEquals("Hermes title", spec.title)
        assertTrue(transcript.items.isEmpty())
        assertNull(chats.read(spec))
        val chat = chats.open(spec, transcript)
        assertEquals("Hermes title", chat.title)
        assertTrue(RecordingView().also(chat::attach).resets.single().isEmpty())
        assertTrue(launches.isEmpty())
        assertEquals(1, acpLaunches.size)
    }

    fun testMissingAcpConfigurationDoesNotRestoreAsClaude() {
        val folder = File(home, "restored").apply { mkdirs() }.path
        assertTrue(File(folder).isDirectory)
        pastSession("acp-id", "A same-id Claude transcript", folder)
        pastSession("native-id", "Claude history", folder)
        openChats.loadState(OpenChats.State().apply {
            chats = mutableListOf(
                OpenChats.Entry().apply { sessionId = "acp-id"; cwd = folder; agentId = "acp" },
                OpenChats.Entry().apply { sessionId = "native-id"; cwd = folder },
            )
        })
        assertEquals(listOf("acp-id", "native-id"), chats.restorable().map { it.first.resumeId })
        settings.acpCommand = ""

        val (spec, transcript) = chats.restorable().single()
        assertEquals("native-id", spec.resumeId)
        assertEquals("claude", openChats.saved().last().agentId)
        assertEquals(listOf("Claude history"), transcript.items.filterIsInstance<ChatItem.UserText>().map { it.text })
        val chat = chats.open(spec, transcript)
        assertEquals("claude", chat.agent.id)
        assertEquals("native-id", launches.single().resumeId)
        assertTrue(acpLaunches.isEmpty())
    }

    fun testProvidersWithTheSameSessionIdAreRememberedSeparately() = inRootOnly {
        pastSession("shared-id", "Claude history")
        openChats.loadState(OpenChats.State())
        openChats.startTracking()
        val native = chats.open(ChatSpec.inProject(root)).also { it.start() }
        connections.single().emit(Fx.init(sessionId = "shared-id", cwd = root))
        val acp = chats.open(ChatSpec.inProject(root))
        assertTrue(acp.selectAgent("acp"))
        acp.start()
        val connection = acpLaunches.single().second
        completeAcp(connection, "initialize", """{"protocolVersion":1,"agentCapabilities":{}}""")
        completeAcp(connection, "session/new", """{"sessionId":"shared-id"}""")

        assertEquals(listOf("claude" to "shared-id", "acp" to "shared-id"), openChats.state.chats.map { it.agentId to it.sessionId })
        assertEquals(setOf("shared-id"), chats.openSessionIds("claude"))
        assertEquals(setOf("shared-id"), chats.openSessionIds("acp"))
        assertEquals(listOf("shared-id" to native), nativeResumeSessions())
        chats.close(native)
        assertEquals(listOf("acp" to "shared-id"), openChats.state.chats.map { it.agentId to it.sessionId })
        assertEquals(setOf("shared-id"), chats.openSessionIds())
        assertTrue(chats.openSessionIds("claude").isEmpty())
        assertEquals(emptyList<Pair<String?, Chat>>(), nativeResumeSessions())
        val archivable = chats.archivable()
        assertEquals(listOf("shared-id"), archivable.map { it.id })
        assertEquals(1, chats.archiveAll(archivable))
        assertEquals(listOf("shared-id"), chats.store.list(root, archived = true).map { it.id })
        assertEquals("shared-id", acp.session.sessionId)
    }

    fun testAResumedClaudeSessionIgnoresTheLastDraftsAcpAgent() {
        pastSession("native-id", "Claude history")
        settings.lastAgent = "acp"
        val chat = chats.open(ChatSpec.resume(root, chats.store.list(root).single()))

        assertTrue(chat.started)
        assertEquals("claude", chat.agent.id)
        assertFalse(chat.selectAgent("acp"))
        assertEquals("native-id", launches.single().resumeId)
        assertEquals(listOf("Claude history"), RecordingView().also(chat::attach).resets.single().filterIsInstance<ChatItem.UserText>().map { it.text })
        assertTrue(acpLaunches.isEmpty())
    }

    fun testAChatOpensWithItsSetupAndOtherwiseTheDefaultMode() {
        val savedMode = settings.defaultMode
        try {
            settings.defaultMode = "acceptEdits"
            chats.open(ChatSpec.inProject(root).copy(setup = SessionSetup(PermissionMode.PLAN, "sonnet", "low"))).start()
            assertEquals(Triple(PermissionMode.PLAN, "sonnet", "low"), launches.last().let { Triple(it.permissionMode, it.model, it.effort) })
            chats.open(ChatSpec.inProject(root).copy(setup = SessionSetup(model = "opus"))).start()
            assertEquals(Triple(PermissionMode.ACCEPT_EDITS, "opus", null), launches.last().let { Triple(it.permissionMode, it.model, it.effort) })
        } finally {
            settings.defaultMode = savedMode
        }
    }

    fun testAResumedChatCanBeReadAheadAndOpenedLater() {
        File(chats.store.projectDir(root).apply { mkdirs() }, "abc.jsonl")
            .writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""")
        val spec = ChatSpec.resume(root, chats.store.list(root).single())
        assertNull(chats.read(ChatSpec.inProject(root)))
        val resumed = chats.read(spec)!!
        File(chats.store.projectDir(root), "abc.jsonl").delete()
        val chat = chats.open(spec, resumed)
        assertEquals("abc", launches.single().resumeId)
        assertEquals(resumed.startedAt, chat.startedAt)
        assertTrue(resumed.items.isNotEmpty())
        assertEquals(resumed.items, RecordingView().also(chat::attach).resets.first())
    }

    fun testStartLaunchesInTheSpecFolder() {
        val chat = chats.open(ChatSpec.inProject("/work/proj")).also { it.start() }
        assertEquals("/work/proj", launches.single().cwd)
        assertNull(launches.single().worktree)
        assertEquals("/work/proj", chat.cwd)
        assertEquals("main", chat.title)
        assertEquals(listOf(chat), chats.all())
    }

    fun testNewWorktreeChatUsesPredictedFolderUntilInitReportsOne() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1")).also { it.start() }
        assertEquals("/work/proj/.claude/worktrees/wt-1", chat.cwd)
        assertEquals("/work/proj", launches.single().cwd)
        assertEquals("wt-1", launches.single().worktree)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals("/elsewhere/real", chat.cwd)
    }

    fun testNewWorktreeChatPassesNameAndAdoptsReportedFolder() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1")).also { it.start() }
        assertEquals("wt-1", launches.single().worktree)
        assertEquals("/work/proj", launches.single().cwd)
        connections.single().emit(Fx.init(cwd = "/work/proj/.claude/worktrees/wt-1"))
        assertEquals("/work/proj/.claude/worktrees/wt-1", chat.cwd)
        assertEquals("wt-1", chat.title)
    }

    fun testChatsAreIndependent() {
        val a = chats.open(ChatSpec.inProject(root)).also { it.start() }
        val b = chats.open(ChatSpec("wt-1", "$root/.claude/worktrees/wt-1")).also { it.start() }
        val viewA = RecordingView().also(a::attach)
        val viewB = RecordingView().also(b::attach)
        a.send("hello")
        assertEquals(listOf("hello"), connections[0].userMessages())
        assertTrue(connections[1].userMessages().isEmpty())
        assertTrue(viewA.items.any { it is ChatItem.UserText })
        assertTrue(viewB.items.isEmpty())
        assertEquals(SessionState.IDLE, b.session.state)
    }

    fun testCloseStopsOnlyThatChat() {
        val a = chats.open(ChatSpec.inProject(root)).also { it.start() }
        val b = chats.open(ChatSpec.inProject(root)).also { it.start() }
        chats.close(a)
        assertTrue(connections[0].stopped)
        assertFalse(connections[1].stopped)
        assertEquals(listOf(b), chats.all())
    }

    fun testAttachReplaysItemsAndState() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        val view = RecordingView()
        chat.attach(view)
        assertEquals(listOf("hi"), view.resets.single().filterIsInstance<ChatItem.UserText>().map { it.text })
        assertEquals(SessionState.RUNNING, view.states.last())
    }

    fun testInsertBeforeAttachIsDeliveredOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.insert("@src/a.txt#L1-2")
        val view = RecordingView()
        chat.attach(view)
        assertEquals(listOf("@src/a.txt#L1-2"), view.inserted)
        chat.insert("@b.txt")
        assertEquals(listOf("@src/a.txt#L1-2", "@b.txt"), view.inserted)
    }

    fun testResumeLoadsHistoryFromTheSessionFolder() {
        val folder = "/work/proj/.claude/worktrees/wt-1"
        File(chats.store.projectDir(folder).apply { mkdirs() }, "abc.jsonl")
            .writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""")
        val chat = chats.open(ChatSpec.resume("/work/proj", SessionSummary("abc", "earlier", 0, folder)))
        assertEquals("abc", launches.single().resumeId)
        assertEquals(folder, launches.single().cwd)
        val view = RecordingView().also(chat::attach)
        assertEquals(listOf(ChatItem.UserText("h-0", "earlier")), view.resets.single())
        assertEquals("wt-1", chat.title)
    }

    fun testResumedChatAnchorsItsNewMessagesAtTheTranscriptsLastAnswer() {
        File(chats.store.projectDir(root).apply { mkdirs() }, "abc.jsonl").writeText(
            """{"type":"user","message":{"role":"user","content":"earlier"}}""" + "\n" +
                """{"type":"assistant","uuid":"a-last","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}""")
        val chat = chats.open(ChatSpec.resume(root, chats.store.list(root).single()))
        chat.send("more")
        awaitMessages(connections.single(), 1)
        connections.single().emit(Fx.result())
        val item = RecordingView().also(chat::attach).resets.last().filterIsInstance<ChatItem.UserText>().last()
        assertTrue(chat.session.rewind(item.id))
        assertEquals("a-last", launches.last().resumeAt)
    }

    fun testResumedSessionKeepsItsName() {
        File(chats.store.projectDir(root).apply { mkdirs() }, "abc.jsonl")
            .writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""")
        val chat = chats.open(ChatSpec.resume(root, chats.store.list(root).single().copy(customTitle = "Login fix")))
        assertEquals("Login fix", chat.title)
    }

    fun testSendingDoesNotRenameAndSecondChatInAFolderIsNumbered() {
        val a = chats.open(ChatSpec.inProject(root))
        a.send("Fix the flaky login test in the auth module")
        assertEquals("main", a.title)
        assertEquals("main 2", chats.open(ChatSpec.inProject(root)).title)
    }

    fun testRenameChangesTitleAndBlankRestoresDefault() {
        val chat = chats.open(ChatSpec.inProject(root))
        var changes = 0
        chat.onTitleChanged = { changes++ }
        chat.rename("  Login   fix ")
        chat.rename("Login fix")
        assertEquals("Login fix", chat.title)
        assertEquals(1, changes)
        chat.rename(" ")
        assertEquals("main", chat.title)
        assertEquals(2, changes)
    }

    fun testRenameIsSavedOnceTheSessionHasAnId() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.rename("Login fix")
        val transcript = File(chats.store.projectDir(root), "sid-1.jsonl")
        chat.send("go")
        connections.single().emit(Fx.init(sessionId = "sid-1", cwd = root))
        awaitTitle(transcript, "Login fix")
        chat.rename("Login fix 2")
        awaitTitle(transcript, "Login fix 2")
    }

    fun testArchiveSavesTheNameMarkedArchived() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.rename("Login fix")
        chat.send("go")
        connections.single().emit(Fx.init(sessionId = "sid-1", cwd = root))
        assertTrue(chat.archive())
        awaitTitle(File(chats.store.projectDir(root), "sid-1.jsonl"), "[archived] Login fix")
    }

    fun testChatWithoutASessionHasNothingToArchive() =
        assertFalse(chats.open(ChatSpec.inProject(root)).archive())

    fun testUnarchiveKeepsTheName() {
        val file = File(chats.store.projectDir(root).apply { mkdirs() }, "abc.jsonl")
        file.writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""" + "\n")
        chats.store.saveTitle(root, "abc", "Login fix", archived = true)
        chats.unarchive(chats.store.list(root, archived = true).single())
        assertEquals("Login fix", chats.store.list(root).single().customTitle)
    }

    private fun pastSession(id: String, firstMessage: String, cwd: String = root) =
        File(chats.store.projectDir(cwd).apply { mkdirs() }, "$id.jsonl")
            .writeText("""{"type":"user","message":{"role":"user","content":"$firstMessage"}}""" + "\n")

    /** Runs [test] with the session folders being the light project's root and no git worktrees. */
    private fun inRootOnly(test: () -> Unit) {
        val saved = chats.worktrees
        chats.worktrees = GitWorktrees(root, brancher = { null }, lister = { null })
        try {
            test()
        } finally {
            chats.worktrees = saved
        }
    }

    fun testArchiveAllArchivesPastSessionsKeepingNamesAndSkipsOpenOnes() = inRootOnly {
        pastSession("old", "earlier")
        chats.store.saveTitle(root, "old", "Login fix")
        pastSession("gone", "already")
        chats.store.saveTitle(root, "gone", "", archived = true)
        pastSession("sid-1", "live")
        val chat = chats.open(ChatSpec.resume(root, chats.store.list(root).single { it.id == "sid-1" }))
        connections.single().emit(Fx.init(sessionId = "sid-1", cwd = root))
        assertEquals("sid-1", chat.session.sessionId)

        val archivable = chats.archivable()
        assertEquals("neither the open nor the archived one", listOf("old"), archivable.map { it.id })
        assertEquals(1, chats.archiveAll(archivable))
        assertEquals(listOf("sid-1"), chats.store.list(root).map { it.id })
        assertEquals("Login fix", chats.store.list(root, archived = true).single { it.id == "old" }.customTitle)
    }

    fun testArchiveAllSkipsASessionOpenedSinceItWasListed() = inRootOnly {
        pastSession("sid-1", "live")
        val listed = chats.archivable()
        assertEquals(listOf("sid-1"), listed.map { it.id })
        chats.open(ChatSpec.resume(root, listed.single()))
        connections.single().emit(Fx.init(sessionId = "sid-1", cwd = root))
        assertEquals(0, chats.archiveAll(listed))
        assertEquals(listOf("sid-1"), chats.store.list(root).map { it.id })
    }

    private fun awaitTitle(transcript: File, title: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (transcript.isFile && transcript.readLines().lastOrNull()?.contains("\"customTitle\":\"$title\"") == true) return
            Thread.sleep(10)
        }
        fail("$title was never saved to $transcript")
    }

    fun testCwdChangeIsReportedOnlyWhenTheFolderChanges() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1")).also { it.start() }
        var changes = 0
        chat.onCwdChanged = { changes++ }
        connections.single().emit(Fx.init(cwd = "/work/proj/.claude/worktrees/wt-1"))
        assertEquals(0, changes)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals(1, changes)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals(1, changes)
    }

    fun testCheckoutTextIsReplayedOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.showCheckout("IDE: wt1")
        val view = object : SessionView by RecordingView() {
            val checkouts = mutableListOf<String>()
            override fun checkout(text: String) { checkouts += text }
        }
        chat.attach(view)
        assertEquals(listOf("IDE: wt1"), view.checkouts)
        chat.showCheckout("IDE: main")
        chat.showCheckout("IDE: main")
        assertEquals(listOf("IDE: wt1", "IDE: main"), view.checkouts)
    }

    fun testContextUsageReachesTheViewAndIsReplayedOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root)).also { it.start() }
        val conn = connections.single()
        conn.emit(Fx.initResponse(conn.lastRequestId("initialize")))
        conn.emit(Fx.contextResponse(conn.lastRequestId("get_context_usage"), used = 76000, max = 200000, percent = 38))
        val view = object : SessionView by RecordingView() {
            val contexts = mutableListOf<tether.cli.ContextUsage>()
            override fun context(usage: tether.cli.ContextUsage) { contexts += usage }
        }
        chat.attach(view)
        assertEquals(listOf(38), view.contexts.map { it.percent })
        chat.send("go")
        conn.emit(Fx.result())
        conn.emit(Fx.contextResponse(conn.lastRequestId("get_context_usage"), used = 90000, max = 200000, percent = 45))
        assertEquals(listOf(38, 45), view.contexts.map { it.percent })
    }

    fun testDeadChatIgnoresSend() {
        val chat = chats.open(ChatSpec.inProject(root)).also { it.start() }
        connections.single().exit(1, "gone")
        chat.send("lost")
        assertTrue(connections.single().userMessages().isEmpty())
        assertEquals("main", chat.title)
    }

    fun testSendPrefixesEditNote() {
        val chat = chats.open(ChatSpec.inProject(root))
        myFixture.configureByText("a.txt", "x")
        myFixture.type("y")
        chat.send("hello")
        awaitMessages(connections.single(), 1)
        val content = connections.single().userMessages().single()
        assertTrue(content, content.startsWith("[IDE] Since your last turn the user edited these files: a.txt."))
        assertTrue(content.endsWith("\n\nhello"))
    }

    fun testEditNotesFollowEachChatsFolder() {
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        myFixture.openFileInEditor(myFixture.addFileToProject(".claude/worktrees/wt1/b.txt", "x").virtualFile)
        myFixture.type("y")
        main.send("first")
        wt.send("second")
        awaitMessages(connections[0], 1)
        awaitMessages(connections[1], 1)
        assertEquals(listOf("first"), connections[0].userMessages())
        assertTrue(connections[1].userMessages().single().startsWith("[IDE] Since your last turn the user edited these files: b.txt."))
    }

    /** Editor context is off: this is about the edit note only. */
    fun testChatOpenedLaterIsNotToldAboutEarlierSaves() {
        settings.attachEditorContext = false
        myFixture.configureByText("early.txt", "x")
        myFixture.type("y")
        FileDocumentManager.getInstance().saveAllDocuments()
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        assertEquals(listOf("hi"), connections.single().userMessages())
    }

    fun testSendPrefixesEditorContext() {
        myFixture.configureByText("calc.js", "function total(a) {\n  return a<caret> + 1;\n}\n")
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("what does this do?")
        awaitMessages(connections.single(), 1)
        assertEquals(
            listOf("[IDE] The user is looking at calc.js, caret at line 2:11, inside function `total` (lines 1–3).\n\nwhat does this do?"),
            connections.single().userMessages())
    }

    fun testEditNoteComesBeforeEditorContext() {
        val chat = chats.open(ChatSpec.inProject(root))
        myFixture.configureByText("a.txt", "x")
        myFixture.type("y")
        chat.send("hello")
        awaitMessages(connections.single(), 1)
        assertEquals(
            listOf("[IDE] Since your last turn the user edited these files: a.txt.\nRe-read them before relying on earlier contents.\n\n" +
                "[IDE] The user is looking at a.txt, caret at line 1:2.\n\nhello"),
            connections.single().userMessages())
    }

    fun testSettingOffSendsNoContext() {
        myFixture.configureByText("a.txt", "x")
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hello")
        assertEquals(listOf("hello"), connections.single().userMessages())
    }

    fun testWorktreeChatNamesTheCounterpart() {
        myFixture.addFileToProject(".claude/worktrees/wt1/src/a.txt", "x")
        myFixture.openFileInEditor(myFixture.addFileToProject("src/a.txt", "x").virtualFile)
        val wt1 = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wt2 = chats.open(ChatSpec("wt2", "$root/.claude/worktrees/wt2"))
        wt1.send("a")
        wt2.send("b")
        awaitMessages(connections[0], 1)
        awaitMessages(connections[1], 1)
        assertEquals(listOf("[IDE] The user is looking at src/a.txt, caret at line 1:1.\n\na"), connections[0].userMessages())
        assertEquals("wt2's folder does not exist yet; git creates it from HEAD, so the main-relative path will hold there",
            listOf("[IDE] The user is looking at src/a.txt, caret at line 1:1.\n\nb"), connections[1].userMessages())
    }

    fun testFailingContextCaptureStillSendsTheMessage() {
        myFixture.configureByText("a.txt", "x")
        myFixture.type("y")
        val connection = FakeConnection()
        val chat = Chat(
            id = 99, spec = ChatSpec.inProject(root), history = emptyList(), connector = { connection },
            editNote = { "[IDE] edit note" },
            editorContext = { throw IllegalStateException("boom") },
        )
        try {
            chat.start()
            chat.send("hello")
            assertEquals(listOf("[IDE] edit note\n\nhello"), connection.userMessages())
        } finally {
            chat.dispose()
        }
    }

    /** A real git repository with one commit, for the git-note tests; null (test skipped) without git. */
    private fun gitRepo(): File? {
        if (!gitAvailable()) return null
        val repo = Files.createTempDirectory("chat-repo").toFile()
        git(repo, "init", "-q", "-b", "main")
        git(repo, "commit", "-q", "--allow-empty", "-m", "one")
        return repo
    }

    private val gitTracker get() = GitTracker.getInstance(project)

    fun testACommitBetweenTurnsIsToldWithTheNextMessage() {
        val repo = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(repo.path))
        gitTracker.drain()
        git(repo, "commit", "-q", "--allow-empty", "-m", "by hand")
        chat.send("hi")
        awaitMessages(connections.single(), 1)
        val message = connections.single().userMessages().single()
        assertTrue(message, message.startsWith("[IDE] Since your last turn, git changed in this checkout"))
        assertTrue(message, message.contains("1 new commit on `main`"))
        assertTrue(message, message.contains("“by hand”"))
        assertTrue(message, message.endsWith("\n\nhi"))
    }

    fun testClaudesOwnCommitsDuringATurnAreNotReportedBack() {
        val repo = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(repo.path))
        gitTracker.drain()
        chat.send("commit it")
        awaitMessages(connections.single(), 1)
        git(repo, "commit", "-q", "--allow-empty", "-m", "by claude")
        connections.single().emit(Fx.result())
        gitTracker.drain()
        chat.send("thanks")
        awaitMessages(connections.single(), 2)
        assertEquals(listOf("commit it", "thanks"), connections.single().userMessages())
    }

    fun testAMessageQueuedDuringATurnGetsNoGitNote() {
        val repo = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(repo.path))
        gitTracker.drain()
        chat.send("one")
        awaitMessages(connections.single(), 1)
        git(repo, "commit", "-q", "--allow-empty", "-m", "mid-turn")
        chat.send("two")
        connections.single().emit(Fx.result())
        awaitMessages(connections.single(), 2)
        assertEquals(listOf("one", "two"), connections.single().userMessages())
    }

    fun testAMessageStoppedBeforeItWentOutLeavesItsGitNoteForTheNext() {
        val repo = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(repo.path))
        gitTracker.drain()
        git(repo, "commit", "-q", "--allow-empty", "-m", "by hand")
        chat.send("dropped")
        chat.interrupt()
        gitTracker.drain()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(emptyList<String>(), connections.single().userMessages())
        chat.send("kept")
        awaitMessages(connections.single(), 1)
        val message = connections.single().userMessages().single()
        assertTrue(message, message.contains("“by hand”"))
        assertTrue(message, message.endsWith("\n\nkept"))
    }

    fun testAnUnnamedChatTakesTheNameOfTheBranchItMovesTo() {
        val repo = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(repo.path))
        assertEquals("main", chat.title)
        var changes = 0
        chat.onTitleChanged = { changes++ }
        chat.send("branch off")
        awaitMessages(connections.single(), 1)
        git(repo, "checkout", "-q", "-b", "login-fix")
        connections.single().emit(Fx.result())
        assertEquals("login-fix", chat.title)
        assertEquals(1, changes)
        connections.single().emit(Fx.init(cwd = repo.path))
        assertEquals("the same branch again is no move", 1, changes)
    }

    fun testANamedChatKeepsItsNameButClearingItShowsTheNewBranch() {
        val repo = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(repo.path))
        chat.rename("Login work")
        var changes = 0
        chat.onTitleChanged = { changes++ }
        chat.send("branch off")
        awaitMessages(connections.single(), 1)
        git(repo, "checkout", "-q", "-b", "login-fix")
        connections.single().emit(Fx.result())
        assertEquals("Login work", chat.title)
        assertEquals(0, changes)
        chat.rename("")
        assertEquals("login-fix", chat.title)
    }

    fun testABranchMoveIsNumberedPastOtherChatsAndAWorktreeBranchUsesItsName() {
        val repo = gitRepo() ?: return
        git(repo, "branch", "worktree-wt-9")
        chats.open(ChatSpec.inProject(repo.path)).rename("wt-9")
        val chat = chats.open(ChatSpec.inProject(repo.path))
        chat.send("go")
        awaitMessages(connections.last(), 1)
        git(repo, "checkout", "-q", "worktree-wt-9")
        connections.last().emit(Fx.result())
        assertEquals("wt-9 2", chat.title)
    }

    private val calc = "function total(a) {\n  return a<caret> + 1;\n}\n"
    private val inTotal = "[IDE] The user is looking at calc.js, caret at line 2:11, inside function `total` (lines 1–3).\n\n"
    private val locationOnly = "[IDE] The user is looking at calc.js, caret at line 2:11.\n\n"

    fun testNoSymbolLookupRunsOnTheEdtOnSend() {
        myFixture.configureByText("calc.js", calc)
        val onEdt = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        contextService.finder = { file, document, offset ->
            onEdt += ApplicationManager.getApplication().isDispatchThread
            tether.context.SymbolFinder.find(file, document, offset)
        }
        val chat = chats.open(ChatSpec.inProject(root))
        chat.attach(RecordingView())
        chat.send("hi")
        awaitMessages(connections.single(), 1)
        assertEquals(listOf(inTotal + "hi"), connections.single().userMessages())
        assertTrue(onEdt.isNotEmpty())
        assertFalse(onEdt.any { it })
    }

    fun testSendWithAWarmCacheIsSynchronous() {
        myFixture.configureByText("calc.js", calc)
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("one")
        awaitMessages(connections.single(), 1)
        connections.single().emit(Fx.result())
        contextService.finder = { _, _, _ -> throw IllegalStateException("the cache should have been used") }
        chat.send("two")
        assertEquals(listOf(inTotal + "one", inTotal + "two"), connections.single().userMessages())
    }

    fun testSendWithAStaleCacheWaitsForTheSymbol() {
        myFixture.configureByText("calc.js", calc)
        val release = CountDownLatch(1)
        contextService.finder = { file, document, offset ->
            release.await(10, TimeUnit.SECONDS)
            tether.context.SymbolFinder.find(file, document, offset)
        }
        val chat = chats.open(ChatSpec.inProject(root))
        val view = RecordingView().also(chat::attach)
        chat.send("wait for it")
        assertTrue(connections.single().userMessages().isEmpty())
        assertTrue("the bubble appears with the send", view.items.none { it is ChatItem.UserText })
        release.countDown()
        awaitMessages(connections.single(), 1)
        assertEquals(listOf(inTotal + "wait for it"), connections.single().userMessages())
    }

    fun testStartingShowsWhileASendWaitsForItsContext() {
        myFixture.configureByText("calc.js", calc)
        val release = CountDownLatch(1)
        contextService.finder = { file, document, offset ->
            release.await(10, TimeUnit.SECONDS)
            tether.context.SymbolFinder.find(file, document, offset)
        }
        val chat = chats.open(ChatSpec.inProject(root))
        val view = RecordingView().also(chat::attach)
        chat.send("wait for it")
        assertEquals(listOf<Activity?>(Activity.Starting), view.activities)
        release.countDown()
        awaitMessages(connections.single(), 1)
        assertEquals("handed to the CLI: still Starting, not repeated", listOf<Activity?>(Activity.Starting), view.activities)
        connections.single().emit(Fx.thinkingStart("m1", 0))
        assertEquals(listOf(Activity.Starting, Activity.Thinking), view.activities)
    }

    fun testStopWhileASendWaitsDropsTheMessage() {
        myFixture.configureByText("calc.js", calc)
        val release = CountDownLatch(1)
        contextService.finder = { file, document, offset ->
            release.await(10, TimeUnit.SECONDS)
            tether.context.SymbolFinder.find(file, document, offset)
        }
        val chat = chats.open(ChatSpec.inProject(root))
        val view = RecordingView().also(chat::attach)
        chat.send("never mind")
        assertEquals(listOf<Activity?>(Activity.Starting), view.activities)
        chat.interrupt()
        assertEquals(listOf<Activity?>(Activity.Starting, null), view.activities)
        release.countDown()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(connections.single().userMessages().isEmpty())
    }

    fun testQueuedMessagesAreReplayedAndGoBackToTheBoxOnEditOrStop() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("go")
        chat.send("second")
        chat.send("third")
        chat.send("fourth")
        val view = RecordingView().also(chat::attach)
        assertEquals(listOf("second", "third", "fourth"), view.queues.single().map { it.text })
        chat.editQueued(view.queues.last()[1].id)
        assertEquals(listOf("third"), view.inserted)
        assertEquals(listOf("second", "fourth"), view.queues.last().map { it.text })
        chat.interrupt()
        assertEquals(listOf("third", "second\n\nfourth"), view.inserted)
        assertTrue(view.queues.last().isEmpty())
    }

    fun testActivityIsReplayedOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("go")
        connections.single().emit(Fx.thinkingStart("m1", 0))
        val view = RecordingView()
        chat.attach(view)
        assertEquals(listOf<Activity?>(Activity.Thinking), view.activities)
    }

    fun testSuggestionReachesTheViewAndIsReplayedOnAttachUntilTheNextTurn() {
        val chat = chats.open(ChatSpec.inProject(root))
        val first = RecordingView()
        chat.attach(first)
        chat.send("go")
        connections.single().emit(Fx.result(), Fx.promptSuggestion("run the tests"))
        assertEquals(listOf("run the tests"), first.suggestions)
        chat.detach(first)
        val second = RecordingView()
        chat.attach(second)
        assertEquals(listOf("run the tests"), second.suggestions)
        chat.send("again")
        val third = RecordingView()
        chat.attach(third)
        assertTrue(third.suggestions.isEmpty())
    }

    fun testSuggestionIsNotReplayedAfterTheProcessDies() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("go")
        connections.single().emit(Fx.result(), Fx.promptSuggestion("run the tests"))
        connections.single().exit(1, "boom")
        val view = RecordingView()
        chat.attach(view)
        assertTrue(view.suggestions.isEmpty())
    }

    fun testBusyFollowsTheTurn() {
        val chat = chats.open(ChatSpec.inProject(root))
        var changes = 0
        chat.onBusyChanged = { changes++ }
        assertFalse(chat.busy)
        chat.send("go")
        assertTrue(chat.busy)
        connections.single().emit(Fx.result())
        assertFalse(chat.busy)
        assertEquals(2, changes)
    }

    fun testALookupThatFailsSendsTheLocationOnly() {
        myFixture.configureByText("calc.js", calc)
        contextService.finder = { _, _, _ -> throw IllegalStateException("boom") }
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        awaitMessages(connections.single(), 1)
        assertEquals(listOf(locationOnly + "hi"), connections.single().userMessages())
    }

    fun testALookupThatTimesOutSendsTheLocationOnly() {
        myFixture.configureByText("calc.js", calc)
        val release = CountDownLatch(1)
        contextService.finder = { file, document, offset ->
            release.await(10, TimeUnit.SECONDS)
            tether.context.SymbolFinder.find(file, document, offset)
        }
        contextService.lookupTimeoutMs = 100
        val chat = chats.open(ChatSpec.inProject(root))
        try {
            chat.send("slow")
            awaitMessages(connections.single(), 1)
            assertEquals(listOf(locationOnly + "slow"), connections.single().userMessages())
        } finally {
            release.countDown()
        }
    }

    fun testQuickSendsKeepTheirOrder() {
        myFixture.configureByText("calc.js", calc)
        val release = CountDownLatch(1)
        contextService.finder = { file, document, offset ->
            release.await(10, TimeUnit.SECONDS)
            tether.context.SymbolFinder.find(file, document, offset)
        }
        val chat = chats.open(ChatSpec.inProject(root))
        val view = RecordingView().also(chat::attach)
        chat.send("first")
        chat.send("second")
        chat.send("third")
        assertTrue(view.items.none { it is ChatItem.UserText })
        release.countDown()
        fun texts() = view.items.filterIsInstance<ChatItem.UserText>().map { it.text }
        fun queued() = view.queues.lastOrNull().orEmpty().map { it.text }
        PlatformTestUtil.waitWithEventsDispatching("Not all sent", { queued().size >= 2 }, 10)
        assertEquals("one claude turn at a time: the rest queue in the session", listOf("first"), texts())
        assertEquals(listOf("second", "third"), queued())
        assertEquals(listOf(inTotal + "first"), connections.single().userMessages())
        connections.single().emit(Fx.result())
        awaitMessages(connections.single(), 2)
        assertTrue(connections.single().userMessages()[1].endsWith("second"))
        assertEquals(listOf("first", "second"), texts())
        assertEquals(listOf("third"), queued())
    }

    /** Keeps dispatching past the lookup and its 200 ms timeout; a stray exception fails the test. */
    fun testClosingTheChatWhileASendWaitsSendsNothingAndThrowsNothing() {
        myFixture.configureByText("calc.js", calc)
        val release = CountDownLatch(1)
        contextService.finder = { file, document, offset ->
            release.await(10, TimeUnit.SECONDS)
            tether.context.SymbolFinder.find(file, document, offset)
        }
        contextService.lookupTimeoutMs = 200
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("pending")
        chats.close(chat)
        release.countDown()
        val pastTheLookupAndItsTimeoutMs = 800
        val until = System.currentTimeMillis() + pastTheLookupAndItsTimeoutMs
        while (System.currentTimeMillis() < until) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue(connections.single().userMessages().isEmpty())
    }

    private fun attachment(id: String) =
        Attachment(id, "$id.png", Image(ImageType.PNG, TestImages.bytes(TestImages.BLUE_PNG_BASE64), 4, 4), "data:image/png;base64,T$id")

    fun testClosingAChatDeletesItsImageFiles() {
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        val written = chat.imageFiles.write("a.png", ImageType.PNG, TestImages.bytes(TestImages.BLUE_PNG_BASE64))!!.toFile()
        assertTrue(written.isFile)
        chats.close(chat)
        assertFalse(written.exists())
    }

    fun testSendCarriesAttachmentsAfterTheNotes() {
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        val view = RecordingView()
        chat.attach(view)
        chat.send("what is this?", listOf(attachment("a"), attachment("b")))
        assertEquals(listOf("what is this?"), connections.single().userMessages())
        assertEquals(listOf(listOf(TestImages.BLUE_PNG_BASE64, TestImages.BLUE_PNG_BASE64)), connections.single().userImages())
        val bubble = view.items.filterIsInstance<ChatItem.UserText>().single()
        assertEquals(listOf("data:image/png;base64,Ta", "data:image/png;base64,Tb"), bubble.images)
    }

    fun testImageOnlyMessageIsSentAndTitledImage() {
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("", listOf(attachment("a")))
        assertEquals(listOf(listOf(TestImages.BLUE_PNG_BASE64)), connections.single().userImages())
        assertEquals("main", chat.title)
    }

    fun testBlankMessageWithoutImagesIsIgnored() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("  ")
        assertTrue(connections.isEmpty())
        assertEquals("main", chat.title)
    }

    fun testBlankMessageWithoutImagesIsIgnoredByAStartedChat() {
        val chat = chats.open(ChatSpec.inProject(root)).also { it.start() }
        chat.send("  ")
        assertTrue(connections.single().userMessages().isEmpty())
        assertEquals("main", chat.title)
    }

    fun testClosingAChatDropsItsPendingAttachments() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.attachments.add(attachment("a"))
        chats.close(chat)
        assertEquals(0, chat.attachments.size)
    }

    fun testOpeningADraftStartsNoProcess() {
        val chat = chats.open(ChatSpec.inProject(root))
        val view = RecordingView().also(chat::attach)
        assertFalse(chat.started)
        assertTrue(launches.isEmpty())
        assertTrue(acpLaunches.isEmpty())
        assertEquals(listOf(SessionState.IDLE), view.states)
        assertNull(chat.session.sessionId)
        assertFalse(chat.busy)
        assertTrue(view.activities.isEmpty())
    }

    fun testChangingTheDraftsAgentStartsNoProcess() {
        val chat = chats.open(ChatSpec.inProject(root))
        assertEquals(listOf("Claude", "Hermes"), chat.agents.map { it.name })
        assertTrue(chat.selectAgent("acp"))
        assertTrue(chat.selectAgent("claude"))
        assertTrue(chat.selectAgent("acp"))
        assertEquals("Hermes", chat.agent.name)
        assertFalse(chat.started)
        assertTrue(launches.isEmpty())
        assertTrue(acpLaunches.isEmpty())
    }

    fun testDraftClaudeSetupSurvivesAnAgentRoundTripWithoutStarting() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.session.setMode("plan")
        chat.session.setModel("sonnet")
        chat.session.setEffort("low")
        assertTrue(chat.selectAgent("acp"))
        assertNull(chat.session.modeId)
        assertTrue(chat.selectAgent("claude"))

        assertEquals("plan", chat.session.modeId)
        assertEquals(SessionSetup(PermissionMode.PLAN, "sonnet", "low"), chat.session.setup)
        assertFalse(chat.started)
        assertTrue(launches.isEmpty())
        assertTrue(acpLaunches.isEmpty())
    }

    fun testFirstSendStartsClaudeOnceAndSendsOnce() {
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        connections.single().emit(Fx.result())
        chat.send("again")
        assertEquals(1, launches.size)
        assertEquals(listOf("hi", "again"), connections.single().userMessages())
        assertTrue(acpLaunches.isEmpty())
    }

    fun testFirstSendStartsTheSelectedAcpAgentOnceAndSendsOnce() {
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        chat.selectAgent("acp")
        chat.send("hi")
        val (command, conn) = acpLaunches.single()
        PlatformTestUtil.waitWithEventsDispatching("initialize was not sent", { conn.sent.isNotEmpty() }, 10)
        fun reply(method: String, result: String) {
            val id = conn.sentJson().last { it.str("method") == method }["id"]
            conn.emit("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        reply("initialize", """{"protocolVersion":1,"agentCapabilities":{}}""")
        reply("session/new", """{"sessionId":"s1"}""")
        val prompts = conn.sentJson().filter { it.str("method") == "session/prompt" }
        assertEquals(listOf("hermes", "acp"), command)
        assertEquals(listOf("hi"), prompts.map { it.obj("params")!!.arr("prompt")!!.first().jsonObject.str("text") })
        assertEquals(1, conn.sentJson().count { it.str("method") == "session/new" })
        assertTrue(launches.isEmpty())
        assertTrue(chat.started)
    }

    fun testTheNextDraftGetsTheLastSelectedAgent() {
        chats.open(ChatSpec.inProject(root)).selectAgent("acp")
        val next = chats.open(ChatSpec.inProject(root))
        assertEquals("acp", next.agent.id)
        next.selectAgent("claude")
        assertEquals("claude", chats.open(ChatSpec.inProject(root)).agent.id)
        assertTrue(acpLaunches.isEmpty())
    }

    fun testAStartedChatKeepsItsAgent() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        assertFalse(chat.selectAgent("acp"))
        assertEquals("claude", chat.agent.id)
        assertEquals(1, launches.size)
        assertTrue(acpLaunches.isEmpty())
    }

    fun testANewWorktreeDraftOffersOnlyClaude() {
        settings.lastAgent = "acp"
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1"))
        assertEquals(listOf("claude"), chat.agents.map { it.id })
        assertEquals("claude", chat.agent.id)
        assertFalse(chat.selectAgent("acp"))
    }

    fun testWithoutAnAcpCommandOnlyClaudeIsOffered() {
        settings.acpCommand = ""
        settings.lastAgent = "acp"
        val chat = chats.open(ChatSpec.inProject(root))
        assertEquals(listOf("claude"), chat.agents.map { it.id })
        assertEquals("claude", chat.agent.id)
    }

    fun testAttachmentsSurviveAnAgentChange() {
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        chat.attachments.add(attachment("a"))
        chat.selectAgent("acp")
        chat.selectAgent("claude")
        chat.send("what is this?", chat.attachments.take(listOf("a")))
        assertEquals(listOf(listOf(TestImages.BLUE_PNG_BASE64)), connections.single().userImages())
    }

    fun testAFailedFirstStartPutsTheMessageBack() {
        chats.connector = { throw ClaudeNotFoundException() }
        val chat = chats.open(ChatSpec.inProject(root))
        val view = RecordingView().also(chat::attach)
        chat.send("keep me")
        assertEquals(listOf("keep me"), view.inserted)
        assertEquals(SessionState.DEAD, chat.session.state)
        assertTrue(view.items.none { it is ChatItem.UserText })
        assertEquals("main", chat.title)
    }
}
