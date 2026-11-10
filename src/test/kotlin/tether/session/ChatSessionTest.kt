package tether.session

import kotlinx.serialization.json.jsonObject
import tether.cli.arr
import tether.cli.CliConnection
import tether.cli.CliMessages
import tether.cli.PermissionMode
import tether.cli.SessionLaunch
import tether.cli.obj
import tether.cli.str
import tether.testutil.FakeConnection
import tether.testutil.Fx
import tether.testutil.RecordingListener
import tether.testutil.TestImages
import tether.image.Image
import tether.image.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private val listener = RecordingListener()
    private val conn get() = connections.last()

    private fun session(
        resumeId: String? = null,
        mode: PermissionMode? = null,
        failConnect: Boolean = false,
        cwd: String = "/proj",
        worktree: String? = null,
        resumeAnchor: String? = null,
        model: String? = null,
        effort: String? = null,
    ) =
        ChatSession(
            clock = { now },
            connect = { launch ->
                if (failConnect) throw IllegalStateException("claude not found")
                launches += launch
                FakeConnection().also { connections += it }
            },
            listener = listener,
            cwd = cwd,
            resumeId = resumeId,
            setup = SessionSetup(mode, model, effort),
            worktree = worktree,
            resumeAnchor = resumeAnchor,
        ).also { it.start() }

    private var now = 1000L

    private val writeInput = """{"file_path":"/p/a.txt","content":"hi"}"""

    // --- activity ---

    @Test fun activityFollowsATurn() {
        val s = session()
        conn.emit(Fx.init())
        assertTrue(listener.activities.isEmpty())
        s.send("go")
        assertEquals(listOf<Activity?>(Activity.Starting), listener.activities)
        conn.emit(Fx.thinkingStart("m1", 0), Fx.thinkingDelta("m1", 0, "a"), Fx.thinkingDelta("m1", 0, "b"))
        conn.emit(Fx.blockStop("m1", 0), Fx.textDelta("m1", 1, "Hi"), Fx.textDelta("m1", 1, " there"))
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/a.txt"}"""))
        conn.emit(Fx.canUseTool("r1", "t1", "Read", """{"file_path":"/p/a.txt"}"""))
        s.respond("r1", PermissionDecision.Allow)
        conn.emit(Fx.toolResult("t1", "x"))
        conn.emit(Fx.result())
        assertEquals(
            listOf<Activity?>(
                Activity.Starting, Activity.Thinking, Activity.Writing, Activity.Running("Read"),
                Activity.Waiting, Activity.Running("Read"), Activity.Thinking, null,
            ),
            listener.activities,
        )
    }

    @Test fun activityIsClearedWhenTheProcessDies() {
        val s = session()
        s.send("go")
        conn.emit(Fx.thinkingStart("m1", 0))
        conn.exit(1, "boom")
        assertNull(listener.activities.last())
        assertEquals(SessionState.DEAD, listener.lastState())
    }

    @Test fun aQueuedMessageStartsANewTurnAtStarting() {
        val s = session()
        s.send("one")
        s.send("two")
        conn.emit(Fx.result())
        assertEquals("unchanged: the next turn also starts there", listOf<Activity?>(Activity.Starting), listener.activities)
        conn.emit(Fx.result())
        assertNull(listener.activities.last())
    }

    @Test fun backgroundAgentsKeepTheActivityAfterTheTurnEnds() {
        val s = session()
        s.send("go")
        conn.emit(Fx.toolUse("m1", "t1", "Agent", "{}"), Fx.backgroundTasks("Count"), Fx.toolResult("t1", "started"))
        conn.emit(Fx.result())
        assertEquals(SessionState.IDLE, s.state)
        assertEquals(Activity.Agents(1), listener.activities.last())
        conn.emit(Fx.backgroundTasks())
        assertNull(listener.activities.last())
    }

    @Test fun backgroundProcessesAreReportedApartFromAgents() {
        val s = session()
        s.send("go")
        conn.emit(Fx.backgroundTaskList(Triple("a1", "local_agent", "Review"), Triple("b1", "local_bash", "npm run dev")))
        assertEquals(listOf(BackgroundProcess("b1", "Bash", "npm run dev", 1000)), listener.processes.last())
        now = 5000
        conn.emit(Fx.backgroundTaskList(Triple("b1", "local_bash", "npm run dev"), Triple("m1", "monitor", "Watch logs")))
        assertEquals(
            "a process keeps the time it was first seen",
            listOf(BackgroundProcess("b1", "Bash", "npm run dev", 1000), BackgroundProcess("m1", "Monitor", "Watch logs", 5000)),
            listener.processes.last(),
        )
        conn.emit(Fx.backgroundTaskList())
        assertEquals(emptyList<BackgroundProcess>(), listener.processes.last())
    }

    @Test fun aBackgroundShellCommandIsNotAnAgent() {
        val s = session()
        s.send("go")
        conn.emit(Fx.toolUse("m1", "t1", "Bash", """{"command":"npm run dev","run_in_background":true}"""), Fx.shellTaskStarted("t1", "b1"))
        conn.emit(Fx.taskNotification("t1"))
        assertNull(listener.latest<ChatItem.ToolCall>().agent)
    }

    @Test fun stopTaskAsksTheCliToStopIt() {
        val s = session()
        s.send("go")
        s.stopTask("b1")
        val stop = conn.sentJson().single { it.str("type") == "control_request" && it.obj("request")?.str("subtype") == "stop_task" }
        assertEquals("b1", stop.obj("request")!!.str("task_id"))
    }

    /** For example, a background agent finished and Claude reads its report. */
    @Test fun aTurnTheCliStartsByItselfShowsAsRunning() {
        val s = session()
        s.send("go")
        conn.emit(Fx.result())
        conn.emit(Fx.messageStart("m2"))
        assertEquals(SessionState.RUNNING, s.state)
        assertEquals(Activity.Thinking, listener.activities.last())
        s.send("queued meanwhile")
        assertEquals(1, conn.userMessages().size)
        conn.emit(Fx.result())
        assertEquals(2, conn.userMessages().size)
    }

    @Test fun aSubagentMessageDoesNotStartATurn() {
        val s = session()
        conn.emit(Fx.subagentMessageStart("m2", "t1"))
        assertEquals(SessionState.IDLE, s.state)
    }

    // --- subagents ---

    private fun startAgent(s: ChatSession, background: Boolean = false) {
        s.send("go")
        conn.emit(Fx.toolUse("m1", "t1", "Agent", """{"description":"Do it","subagent_type":"general-purpose","prompt":"Do it"}"""))
        conn.emit(Fx.taskStarted("t1", background, "Do it"))
    }

    @Test fun anAgentCallCarriesItsAgentInfo() {
        val s = session()
        startAgent(s, background = true)
        conn.emit(Fx.taskProgress("t1", 2, 900, "Bash"))
        assertEquals(AgentInfo("general-purpose", true, "Do it", AgentState.RUNNING, 2, 900, "Bash", taskId = "a1"), listener.latest<ChatItem.ToolCall>().agent)
        conn.emit(Fx.taskNotification("t1", report = "All done"))
        val done = listener.latest<ChatItem.ToolCall>().agent!!
        assertEquals(AgentState.DONE, done.state)
        assertEquals("All done", done.report)
        assertEquals(3, done.toolUses)
        assertNull(done.lastTool)
    }

    private fun resumeAgent() {
        conn.emit(Fx.toolUse("m2", "t5", "SendMessage", """{"to":"a1","message":"One more thing"}"""))
        conn.emit(Fx.taskStarted("t5", true, "One more thing"))
    }

    private fun agentCard() = listener.current().filterIsInstance<ChatItem.ToolCall>().single { it.id == "t1" }

    @Test fun aResumedAgentRunsAgainOnItsFirstCard() {
        val s = session()
        startAgent(s, background = true)
        conn.emit(Fx.taskNotification("t1", report = "All done"))
        resumeAgent()
        assertEquals(AgentInfo("general-purpose", true, "Do it", AgentState.RUNNING, taskId = "a1"), agentCard().agent)
        assertNull(listener.current().filterIsInstance<ChatItem.ToolCall>().single { it.id == "t5" }.agent)
        conn.emit(Fx.taskProgress("t5", 1, 300, "Read"))
        assertEquals("Read", agentCard().agent!!.lastTool)
        conn.emit(Fx.taskNotification("t5", report = "Also done"))
        assertEquals(AgentState.DONE, agentCard().agent!!.state)
        assertEquals("Also done", agentCard().agent!!.report)
    }

    @Test fun aResumedAgentsItemsGoOnItsFirstCard() {
        val s = session()
        startAgent(s, background = true)
        conn.emit(Fx.taskNotification("t1"))
        resumeAgent()
        conn.emit(Fx.subagentText("t5", "sm2", "Looking again"))
        conn.emit(Fx.subagentToolUse("t5", "t6", "Bash", """{"command":"ls"}"""))
        assertEquals("t1", listener.latest<ChatItem.AssistantText>().parentId)
        assertEquals("t1", listener.latest<ChatItem.ToolCall>().parentId)
    }

    @Test fun aFailedAgentIsFailed() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.taskNotification("t1", "failed", null))
        assertEquals(AgentState.FAILED, listener.latest<ChatItem.ToolCall>().agent!!.state)
    }

    @Test fun taskEventsBeforeTheCallAreKept() {
        val s = session()
        s.send("go")
        conn.emit(Fx.taskStarted("t1", true, "Do it"))
        conn.emit(Fx.toolUse("m1", "t1", "Agent", "{}"))
        assertEquals(true, listener.latest<ChatItem.ToolCall>().agent!!.background)
    }

    @Test fun subagentItemsCarryTheirParentAndLeaveThePhaseAlone() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.subagentText("t1", "sm1", "Looking"))
        conn.emit(Fx.subagentToolUse("t1", "t2", "Bash", """{"command":"ls"}"""))
        assertEquals(Activity.Running("Agent"), listener.activities.last())
        conn.emit(Fx.subagentToolResult("t1", "t2", "a.txt"))
        assertEquals(Activity.Running("Agent"), listener.activities.last())
        assertEquals("t1", listener.latest<ChatItem.AssistantText>().parentId)
        val bash = listener.latest<ChatItem.ToolCall>()
        assertEquals("t2", bash.id)
        assertEquals("t1", bash.parentId)
        assertEquals(ToolStatus.DONE, bash.status)
    }

    @Test fun handbackCallsAreNotShown() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.subagentToolUse("t1", "t3", "SubagentHandback", """{"text":"x"}"""), Fx.subagentToolResult("t1", "t3", "ok"))
        assertTrue(listener.items.none { it.id == "t3" })
        assertEquals(Activity.Running("Agent"), listener.activities.last())
    }

    @Test fun aSubagentPermissionRequestKeepsItsParent() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.subagentToolUse("t1", "t2", "Bash", """{"command":"ls"}"""))
        conn.emit(Fx.canUseTool("r1", "t2", "Bash", """{"command":"ls"}"""))
        val bash = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.AWAITING_PERMISSION, bash.status)
        assertEquals("t1", bash.parentId)
    }

    @Test fun aForegroundAgentStillRunningWhenTheTurnEndsFailed() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.result())
        assertEquals(AgentState.FAILED, listener.latest<ChatItem.ToolCall>().agent!!.state)
    }

    @Test fun aBackgroundAgentOutlivesTheTurnButNotTheProcess() {
        val s = session()
        startAgent(s, background = true)
        conn.emit(Fx.result())
        assertEquals(AgentState.RUNNING, listener.current().filterIsInstance<ChatItem.ToolCall>().single { it.id == "t1" }.agent!!.state)
        conn.exit(1, "boom")
        assertEquals(AgentState.FAILED, listener.latest<ChatItem.ToolCall>().agent!!.state)
    }

    @Test fun allowingASubagentToolKeepsTheStripOnTheAgent() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.subagentToolUse("t1", "t2", "Bash", """{"command":"ls"}"""), Fx.canUseTool("r1", "t2", "Bash", """{"command":"ls"}"""))
        s.respond("r1", PermissionDecision.Allow)
        assertEquals(Activity.Running("Agent"), listener.activities.last())
    }

    @Test fun unchangedProgressIsNotReSent() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.taskProgress("t1", 2, 900, "Bash"))
        val before = listener.items.size
        conn.emit(Fx.taskProgress("t1", 2, 900, "Bash"))
        assertEquals(before, listener.items.size)
    }

    @Test fun longReportsAreCut() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.taskNotification("t1", report = "x".repeat(200_000)))
        val report = listener.latest<ChatItem.ToolCall>().agent!!.report!!
        assertEquals(MAX_AGENT_TEXT + 1, report.length)
        assertTrue(report.endsWith("\u2026"))
    }

    // --- lifecycle and turns ---

    @Test fun startSendsInitializeAndIsIdle() {
        session()
        assertEquals("initialize", conn.sentJson().single().obj("request")!!.str("subtype"))
        assertEquals(SessionState.IDLE, listener.lastState())
    }

    @Test fun initResponseAndSystemInitSetModeAndSessionId() {
        val s = session()
        conn.emit(Fx.initResponse(conn.lastRequestId("initialize"), "plan"))
        assertEquals(PermissionMode.PLAN, s.mode)
        conn.emit(Fx.init("sess-9", "acceptEdits"))
        assertEquals("sess-9", s.sessionId)
        assertEquals(PermissionMode.ACCEPT_EDITS, s.mode)
    }

    @Test fun sendShowsUserTextAndSendsPrefixedPayload() {
        val s = session()
        s.send("hello", prefix = "[IDE] note")
        assertEquals("hello", listener.latest<ChatItem.UserText>().text)
        assertEquals(listOf("[IDE] note\n\nhello"), conn.userMessages())
        assertEquals(SessionState.RUNNING, s.state)
    }

    private val red = Image(ImageType.PNG, TestImages.bytes(TestImages.RED_PNG_BASE64), 4, 4)

    @Test fun sendWithImagesSendsAContentArrayAndShowsThumbnails() {
        val s = session()
        s.send("what is this?", prefix = "[IDE] note", images = listOf(red), thumbs = listOf("data:image/png;base64,T"))
        val item = listener.latest<ChatItem.UserText>()
        assertEquals("what is this?", item.text)
        assertEquals(listOf("data:image/png;base64,T"), item.images)
        assertEquals(listOf("[IDE] note\n\nwhat is this?"), conn.userMessages())
        assertEquals(listOf(listOf(TestImages.RED_PNG_BASE64)), conn.userImages())
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun imageOnlyMessageWithoutNotesHasNoTextBlock() {
        val s = session()
        s.send("", images = listOf(red, red), thumbs = listOf("", ""))
        val content = conn.sentJson().last().obj("message")!!["content"].toString()
        assertEquals(2, Regex("\"type\":\"image\"").findAll(content).count())
        assertFalse(content.contains("\"type\":\"text\""))
    }

    @Test fun queuedMessageKeepsItsImages() {
        val s = session()
        s.send("first")
        s.send("second", images = listOf(red))
        assertEquals(listOf("first"), conn.userMessages())
        conn.emit(Fx.result())
        assertEquals(listOf("first", "second"), conn.userMessages())
        assertEquals(listOf(emptyList(), listOf(TestImages.RED_PNG_BASE64)), conn.userImages())
    }

    @Test fun aSentMessageKeepsItsImageFiles() {
        val s = session()
        s.send("look", images = listOf(red, red), thumbs = listOf("data:image/png;base64,T", ""), files = listOf("/tmp/a.png", ""))
        val id = listener.latest<ChatItem.UserText>().id
        assertEquals("/tmp/a.png", s.imageFile(id, 0))
        assertNull(s.imageFile(id, 1))
        assertNull(s.imageFile(id, 2))
        assertNull(s.imageFile("user-unknown", 0))
    }

    @Test fun aQueuedMessageKeepsItsFilesUnderTheItemItBecomes() {
        val s = session()
        s.send("first")
        s.send("second", images = listOf(red), thumbs = listOf("data:image/png;base64,T"), files = listOf("/tmp/b.png"))
        conn.emit(Fx.result())
        assertEquals("/tmp/b.png", s.imageFile(listener.latest<ChatItem.UserText>().id, 0))
    }

    @Test fun aToolCallIsFoundByItsId() {
        val s = session()
        s.send("read it")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/proj/blue.png"}"""))
        assertEquals("/proj/blue.png", s.toolCall("t1")!!.filePath)
        assertNull(s.toolCall("t-unknown"))
    }

    private fun userTexts() = listener.current().filterIsInstance<ChatItem.UserText>().map { it.text }

    @Test fun aQueuedMessageIsShownQueuedAndEntersTheLogWhenItGoesOut() {
        val s = session()
        s.send("first")
        s.send("second", images = listOf(red), thumbs = listOf("data:image/png;base64,T"))
        assertEquals(listOf("first"), userTexts())
        assertEquals(listOf("second"), listener.queuedTexts())
        assertEquals(listOf("data:image/png;base64,T"), listener.queues.last().single().images)
        conn.emit(Fx.result())
        assertEquals(emptyList<String>(), listener.queuedTexts())
        val current = listener.current()
        assertTrue(current.indexOfFirst { it is ChatItem.TurnResult } < current.indexOfLast { it is ChatItem.UserText })
        assertEquals(listOf("first", "second"), userTexts())
        assertEquals(listOf("data:image/png;base64,T"), listener.latest<ChatItem.UserText>().images)
    }

    @Test fun unqueueTakesAMessageOutWithoutSendingIt() {
        val s = session()
        s.send("first")
        s.send("second")
        s.send("third")
        val id = listener.queues.last().first().id
        assertEquals("second", s.unqueue(id)?.text)
        assertNull(s.unqueue(id))
        assertEquals(listOf("third"), listener.queuedTexts())
        conn.emit(Fx.result())
        assertEquals(listOf("first", "third"), conn.userMessages())
    }

    @Test fun sendNowStopsTheTurnAndSendsThatMessageFirst() {
        val s = session()
        s.send("first")
        s.send("second")
        s.send("third")
        s.sendNow(listener.queues.last().last().id)
        assertEquals(listOf("third", "second"), listener.queuedTexts())
        assertEquals("interrupt", conn.sentJson().last().obj("request")!!.str("subtype"))
        assertTrue(listener.dropped.isEmpty())
        conn.emit(Fx.interruptedUser(), Fx.errorResult())
        assertEquals(listOf("first", "third"), conn.userMessages())
        assertEquals(listOf("second"), listener.queuedTexts())
        assertEquals(SessionState.RUNNING, s.state)
        conn.emit(Fx.result())
        assertEquals(listOf("first", "third", "second"), conn.userMessages())
    }

    @Test fun interruptDropsTheQueueAndReportsIt() {
        val s = session()
        s.send("first")
        s.send("second", images = listOf(red), thumbs = listOf(""))
        s.interrupt()
        assertEquals(emptyList<String>(), listener.queuedTexts())
        assertEquals(listOf("second"), listener.dropped.single().map { it.text })
        assertEquals("1 queued image was not sent.", listener.latest<ChatItem.Notice>().text)
    }

    @Test fun theQueueIsDroppedWhenTheProcessDies() {
        val s = session()
        s.send("first")
        s.send("second")
        conn.exit(1, "boom")
        assertEquals(emptyList<String>(), listener.queuedTexts())
        assertEquals(listOf("second"), listener.dropped.single().map { it.text })
    }

    @Test fun noticeIsShownInTheChat() {
        val s = session()
        s.notice("Couldn't attach a.bmp: not a PNG, JPEG, GIF, WebP or HEIC image.")
        val notice = listener.latest<ChatItem.Notice>()
        assertEquals("Couldn't attach a.bmp: not a PNG, JPEG, GIF, WebP or HEIC image.", notice.text)
        assertTrue(notice.isError)
    }

    @Test fun streamedTextIsOneItemAndFinalAssistantMessageIsNotDuplicated() {
        val s = session()
        s.send("hi")
        conn.emit(Fx.messageStart("m1"), Fx.textDelta("m1", 1, "DO"), Fx.textDelta("m1", 1, "NE"))
        val streaming = listener.latest<ChatItem.AssistantText>()
        assertEquals("DONE", streaming.text)
        assertTrue(streaming.streaming)
        conn.emit(Fx.blockStop("m1", 1), Fx.assistantText("m1", "DONE"))
        val texts = listener.current().filterIsInstance<ChatItem.AssistantText>()
        assertEquals(1, texts.size)
        assertFalse(texts.single().streaming)
    }

    @Test fun assistantTextWithoutStreamingCreatesItem() {
        val s = session()
        s.send("hi")
        conn.emit(Fx.assistantText("m2", "plain"))
        assertEquals(ChatItem.AssistantText(listener.latest<ChatItem.AssistantText>().id, "plain", false), listener.latest<ChatItem.AssistantText>())
    }

    @Test fun resultEndsTurnAndFlushesQueue() {
        val s = session()
        s.send("one")
        s.send("two")
        assertEquals(listOf("one"), conn.userMessages())
        conn.emit(Fx.result())
        assertEquals(listOf("one", "two"), conn.userMessages())
        assertEquals(SessionState.RUNNING, s.state)
        conn.emit(Fx.result())
        assertEquals(SessionState.IDLE, s.state)
        val result = listener.latest<ChatItem.TurnResult>()
        assertFalse(result.isError)
        assertEquals(5566L, result.durationMs)
    }

    @Test fun suggestionAfterTheTurnIsPassedOnTrimmed() {
        val s = session()
        s.send("one")
        conn.emit(Fx.result(), Fx.promptSuggestion("  run the tests \n"))
        assertEquals(listOf("run the tests"), listener.suggestions)
    }

    @Test fun suggestionIsDroppedWhileATurnRunsOrIsQueuedOrWhenBlank() {
        val s = session()
        s.send("one")
        conn.emit(Fx.promptSuggestion("early"))
        s.send("two")
        conn.emit(Fx.result(), Fx.promptSuggestion("stale"))
        assertEquals(SessionState.RUNNING, s.state)
        conn.emit(Fx.result(), Fx.promptSuggestion("   "))
        assertTrue(listener.suggestions.isEmpty())
    }

    @Test fun longSuggestionIsDropped() {
        val s = session()
        s.send("one")
        conn.emit(Fx.result(), Fx.promptSuggestion("x".repeat(MAX_SUGGESTION_CHARS + 1)))
        assertTrue(listener.suggestions.isEmpty())
    }

    @Test fun errorResultCarriesText() {
        val s = session()
        s.send("x")
        conn.emit(Fx.errorResult())
        val r = listener.latest<ChatItem.TurnResult>()
        assertTrue(r.isError)
        assertEquals("[ede_diagnostic] result_type=user", r.text)
    }

    // --- tools and permissions ---

    @Test fun permissionRequestMarksToolAndState() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.AWAITING_PERMISSION, tool.status)
        assertEquals("r1", tool.permissionRequestId)
        assertEquals("/p/a.txt", tool.filePath)
        assertEquals(SessionState.AWAITING_PERMISSION, s.state)
    }

    @Test fun allowSendsAllowWithOriginalInput() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.Allow)
        val response = conn.sentJson().last().obj("response")!!
        assertEquals("r1", response.str("request_id"))
        assertEquals("allow", response.obj("response")!!.str("behavior"))
        assertEquals("hi", response.obj("response")!!.obj("updatedInput")!!.str("content"))
        assertEquals(ToolStatus.RUNNING, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun denySendsDenyAndToolEndsDenied() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.Deny("use b.txt"))
        assertEquals(CliMessages.deny("r1", "use b.txt"), conn.sent.last())
        conn.emit(Fx.toolResult("t1", "use b.txt", isError = true))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun allowForSessionAutoAllowsLaterRequestsForSameTool() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.AllowForSession)
        conn.emit(Fx.toolUse("m2", "t2", "Write", writeInput), Fx.canUseTool("r2", "t2", "Write", writeInput))
        assertEquals("r2", conn.sentJson().last().obj("response")!!.str("request_id"))
        assertEquals(ToolStatus.RUNNING, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun editResultEnablesDiff() {
        val s = session()
        s.send("x")
        conn.emit(
            Fx.toolUse("m1", "t1", "Edit", """{"file_path":"/p/f.txt","old_string":"two","new_string":"2"}"""),
            Fx.toolResult("t1", "ok", toolUseResultJson = Fx.editResult("/p/f.txt", "line two\n")),
        )
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.DONE, tool.status)
        assertTrue(tool.hasDiff)
        assertEquals("ok", tool.resultPreview)
        assertEquals(FileDiff("/p/f.txt", "line two\n"), s.diffFor("t1"))
    }

    @Test fun createdFileHasNoDiff() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.toolResult("t1", "ok", toolUseResultJson = Fx.createResult("/p/a.txt")))
        assertFalse(listener.latest<ChatItem.ToolCall>().hasDiff)
        assertNull(s.diffFor("t1"))
    }

    // --- files a turn edited ---

    private fun edit(id: String, file: File, original: String) = arrayOf(
        Fx.toolUse("m1", id, "Edit", """{"file_path":${Fx.q(file.path)},"old_string":"a","new_string":"b"}"""),
        Fx.toolResult(id, "ok", toolUseResultJson = Fx.editResult(file.path, original)),
    )

    private fun turnFiles() = listener.latest<ChatItem.TurnResult>().files

    @Test fun resultListsEachEditedFileOnceAgainstItsContentBeforeTheTurn() {
        val a = tmp.newFile("a.txt").apply { writeText("one\n2\nthree\nfour\n") }
        val s = session()
        s.send("x")
        conn.emit(*edit("t1", a, "one\ntwo\nthree\n"))
        conn.emit(*edit("t2", a, "one\n2\nthree\n"))
        conn.emit(Fx.result())
        assertEquals(listOf(TurnFile(a.path, added = 2, removed = 1, isNew = false)), turnFiles())
        assertEquals(listOf(FileDiff(a.path, "one\ntwo\nthree\n")), s.turnDiffs(listener.latest<ChatItem.TurnResult>().id))
    }

    @Test fun aCreatedFileIsNewAndAllItsLinesAreAdded() {
        val a = tmp.newFile("a.txt").apply { writeText("x\ny\n") }
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", """{"file_path":${Fx.q(a.path)},"content":"x\ny\n"}"""),
            Fx.toolResult("t1", "ok", toolUseResultJson = Fx.createResult(a.path)))
        conn.emit(Fx.result())
        assertEquals(listOf(TurnFile(a.path, added = 2, removed = 0, isNew = true)), turnFiles())
        assertEquals(listOf(FileDiff(a.path, "", isNew = true)), s.turnDiffs(listener.latest<ChatItem.TurnResult>().id))
    }

    @Test fun aSubagentsEditsCount() {
        val a = tmp.newFile("a.txt").apply { writeText("new\n") }
        startAgent(session())
        conn.emit(Fx.subagentToolUse("t1", "s1", "Edit", """{"file_path":${Fx.q(a.path)}}"""),
            Fx.subagentToolResult("t1", "s1", "ok", toolUseResultJson = Fx.editResult(a.path, "old\n")))
        conn.emit(Fx.toolResult("t1", "DONE"), Fx.result())
        assertEquals(listOf(TurnFile(a.path, added = 1, removed = 1, isNew = false)), turnFiles())
    }

    @Test fun filesAreListedInTheOrderTheTurnFirstEditedThem() {
        val b = tmp.newFile("b.txt").apply { writeText("b2\n") }
        val a = tmp.newFile("a.txt").apply { writeText("a2\n") }
        session().send("x")
        conn.emit(*edit("t1", b, "b1\n"))
        conn.emit(*edit("t2", a, "a1\n"))
        conn.emit(*edit("t3", b, "b1.5\n"))
        conn.emit(Fx.result())
        assertEquals(listOf(b.path, a.path), turnFiles().map { it.path })
    }

    @Test fun aFileBackToHowItWasIsLeftOut() {
        val a = tmp.newFile("a.txt").apply { writeText("same\n") }
        session().send("x")
        conn.emit(*edit("t1", a, "same\n"))
        conn.emit(Fx.result())
        assertEquals(emptyList<TurnFile>(), turnFiles())
    }

    @Test fun failedEditsAndReadsDoNotCount() {
        val a = tmp.newFile("a.txt").apply { writeText("x\n") }
        session().send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Edit", """{"file_path":${Fx.q(a.path)}}"""),
            Fx.toolResult("t1", "no match", isError = true, toolUseResultJson = Fx.editResult(a.path, "y\n")))
        conn.emit(Fx.toolUse("m1", "t2", "Read", """{"file_path":${Fx.q(a.path)}}"""), Fx.toolResult("t2", "x"))
        conn.emit(Fx.result())
        assertEquals(emptyList<TurnFile>(), turnFiles())
    }

    @Test fun eachTurnListsOnlyItsOwnEdits() {
        val a = tmp.newFile("a.txt").apply { writeText("2\n") }
        val b = tmp.newFile("b.txt").apply { writeText("2\n") }
        val s = session()
        s.send("x")
        conn.emit(*edit("t1", a, "1\n"))
        conn.emit(Fx.result())
        val first = listener.latest<ChatItem.TurnResult>().id
        s.send("y")
        conn.emit(*edit("t2", b, "1\n"))
        conn.emit(Fx.result())
        assertEquals(listOf(b.path), turnFiles().map { it.path })
        assertEquals(listOf(FileDiff(a.path, "1\n")), s.turnDiffs(first))
    }

    @Test fun anInterruptedTurnsEditsDoNotCarryIntoTheNext() {
        val a = tmp.newFile("a.txt").apply { writeText("2\n") }
        val s = session()
        s.send("x")
        conn.emit(*edit("t1", a, "1\n"))
        s.interrupt()
        conn.emit(Fx.result())
        s.send("y")
        conn.emit(Fx.result())
        assertEquals(emptyList<TurnFile>(), turnFiles())
    }

    @Test fun editsOfATurnTheProcessDiedInDoNotCarryIntoTheNext() {
        val a = tmp.newFile("a.txt").apply { writeText("2\n") }
        val s = session()
        s.send("x")
        conn.emit(*edit("t1", a, "1\n"))
        conn.exit(1, "boom")
        s.restart()
        s.send("y")
        conn.emit(Fx.result())
        assertEquals(emptyList<TurnFile>(), turnFiles())
    }

    @Test fun anInterruptTheProcessDiedBeforeAnsweringDoesNotMarkTheNextTurn() {
        val s = session()
        s.send("x")
        s.interrupt()
        conn.exit(1, "boom")
        s.restart()
        s.send("y")
        conn.emit(Fx.result())
        assertFalse(listener.latest<ChatItem.TurnResult>().isError)
        assertFalse(listener.items.filterIsInstance<ChatItem.Notice>().any { it.text == "Interrupted" })
    }

    @Test fun readResultHasNoDiff() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/f.txt"}"""), Fx.toolResult("t1", "contents"))
        assertFalse(listener.latest<ChatItem.ToolCall>().hasDiff)
        assertNull(s.diffFor("t1"))
    }

    @Test fun readOfAnImageShowsItInTheToolRow() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/blue.png"}"""), Fx.readImageResult("t1", TestImages.BLUE_PNG_BASE64))
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.DONE, tool.status)
        assertEquals(listOf("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}"), tool.images)
        assertEquals("", tool.resultPreview)
        assertFalse(tool.hasDiff)
    }

    @Test fun atMostSixImagesOfOneToolResultAreShown() {
        val s = session()
        s.send("x")
        val block = """{"type":"image","source":{"type":"base64","data":"${TestImages.BLUE_PNG_BASE64}","media_type":"image/png"}}"""
        val line = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":[${List(10) { block }.joinToString(",")}]}]},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u12"}"""
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/blue.png"}"""), line)
        val images = listener.latest<ChatItem.ToolCall>().images
        assertEquals(10, images.size)
        assertEquals(6, images.count { it.startsWith("data:image/png;base64,") })
        assertEquals(List(4) { "" }, images.drop(6))
    }

    @Test fun anImageBlockThatIsNotAnImageShowsNothing() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/x.png"}"""), Fx.readImageResult("t1", "AAAA"), Fx.readImageResult("t1", "%%%"))
        assertEquals(listOf(""), listener.latest<ChatItem.ToolCall>().images)
    }

    // --- control ---

    @Test fun interruptSendsControlRequestAndShowsNotice() {
        val s = session()
        s.send("long task")
        s.send("queued")
        s.interrupt()
        assertEquals("interrupt", conn.sentJson().last().obj("request")!!.str("subtype"))
        conn.emit(Fx.interruptedUser(), Fx.errorResult())
        assertEquals("Interrupted", listener.latest<ChatItem.Notice>().text)
        assertTrue(listener.items.none { it is ChatItem.TurnResult })
        assertEquals(listOf("long task"), conn.userMessages())
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun interruptDeniesPendingPermission() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.interrupt()
        val sent = conn.sentJson()
        assertEquals("deny", sent[sent.size - 2].obj("response")!!.obj("response")!!.str("behavior"))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun setPermissionModeSendsRequest() {
        val s = session()
        s.setPermissionMode(PermissionMode.ACCEPT_EDITS)
        val req = conn.sentJson().last().obj("request")!!
        assertEquals("set_permission_mode", req.str("subtype"))
        assertEquals("acceptEdits", req.str("mode"))
        assertEquals(PermissionMode.ACCEPT_EDITS, s.mode)
    }

    @Test fun failedControlRequestShowsNotice() {
        session()
        conn.emit(Fx.errorResponse("x", "unsupported"))
        assertTrue(listener.latest<ChatItem.Notice>().text.contains("unsupported"))
    }

    @Test fun processExitMarksDeadAndRestartResumesWithMode() {
        val s = session(mode = PermissionMode.PLAN)
        s.send("x")
        conn.emit(Fx.init("sess-7", "plan"))
        conn.exit(1, "boom")
        assertEquals(SessionState.DEAD, s.state)
        val notice = listener.latest<ChatItem.Notice>()
        assertTrue(notice.isError)
        assertTrue(notice.text.contains("boom"))
        s.send("ignored while dead")
        assertEquals(listOf("x"), connections.first().userMessages())

        s.restart()
        assertEquals(SessionLaunch("sess-7", PermissionMode.PLAN, "/proj"), launches.last())
        assertEquals(2, connections.size)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun linesFromDisposedConnectionAreIgnored() {
        val s = session()
        val old = conn
        s.dispose()
        assertTrue(old.stopped)
        val before = listener.items.size
        old.emit(Fx.assistantText("m1", "late"))
        assertEquals(before, listener.items.size)
    }

    @Test fun connectFailureIsReportedAsDead() {
        session(failConnect = true)
        assertEquals(SessionState.DEAD, listener.lastState())
        assertEquals("claude not found", listener.latest<ChatItem.Notice>().text)
    }

    @Test fun resumeIdAndModeArePassedToFirstLaunch() {
        session(resumeId = "abc", mode = PermissionMode.AUTO)
        assertEquals(SessionLaunch("abc", PermissionMode.AUTO, "/proj"), launches.single())
    }

    private class ThrowingSendConnection : CliConnection {
        var stopped = false
        var onExit: ((Int, String) -> Unit)? = null
        override fun start(onLine: (String) -> Unit, onExit: (Int, String) -> Unit) { this.onExit = onExit }
        override fun send(line: String) { throw IllegalStateException("pipe closed") }
        override fun stop() { stopped = true }
    }

    @Test fun startFailureAfterConnectStopsConnectionAndIgnoresLateExit() {
        val bad = ThrowingSendConnection()
        val s = ChatSession(connect = { bad }, listener = listener, cwd = "/proj")
        s.start()
        assertEquals(SessionState.DEAD, s.state)
        assertEquals(1, listener.items.filterIsInstance<ChatItem.Notice>().size)
        assertTrue(bad.stopped)
        bad.onExit!!(1, "late")
        assertEquals(1, listener.items.filterIsInstance<ChatItem.Notice>().size)
    }

    // --- rewind ---

    private fun userIds() = listener.current().filterIsInstance<ChatItem.UserText>().map { it.id }

    /** One finished turn: [text] goes out, claude answers with assistant message [uuid]. */
    private fun turn(s: ChatSession, text: String, uuid: String) {
        s.send(text)
        conn.emit(Fx.assistantText("m-$uuid", "ok", uuid = uuid), Fx.result())
    }

    @Test fun rewindResumesForkedAtTheLastAnswerBeforeTheMessage() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        turn(s, "two", "a-2")
        val old = conn
        assertTrue(s.rewind(userIds()[1]))
        assertTrue(old.stopped)
        assertEquals(SessionLaunch("sess-1", PermissionMode.DEFAULT, "/proj", resumeAt = "a-1", fork = true), launches.last())
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun rewindingTheFirstMessageStartsAFreshSession() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        assertTrue(s.rewind(userIds()[0]))
        assertEquals(SessionLaunch(null, PermissionMode.DEFAULT, "/proj"), launches.last())
        assertNull(s.sessionId)
    }

    @Test fun theForkIsKeptUntilClaudeReportsItsNewSession() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        turn(s, "two", "a-2")
        s.rewind(userIds()[1])
        assertNull("the id is unknown until system/init: nothing may be saved under the old one meanwhile", s.sessionId)
        conn.exit(1, "")
        s.restart()
        assertEquals(SessionLaunch("sess-1", PermissionMode.DEFAULT, "/proj", resumeAt = "a-1", fork = true), launches.last())
        conn.emit(Fx.init("sess-2"))
        assertEquals("sess-2", s.sessionId)
        conn.exit(1, "")
        s.restart()
        assertEquals(SessionLaunch("sess-2", PermissionMode.DEFAULT, "/proj"), launches.last())
    }

    @Test fun aResumedChatsFirstNewMessageIsAnchoredAtTheTranscriptsLastAnswer() {
        val s = session(resumeId = "sess-1", resumeAnchor = "a-0")
        s.send("more")
        conn.emit(Fx.result())
        assertTrue(s.rewind(userIds()[0]))
        assertEquals(SessionLaunch("sess-1", null, "/proj", resumeAt = "a-0", fork = true), launches.last())
    }

    @Test fun anAnchoredMessageWithNoSessionToResumeIsNotRewound() {
        val s = session(resumeAnchor = "a-0")
        s.send("more")
        conn.emit(Fx.result())
        assertFalse(s.rewind(userIds()[0]))
        assertEquals(1, launches.size)
        assertTrue(s.canRewindTo(userIds()[0]))
    }

    @Test fun aRunningTurnIsNotRewound() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        s.send("one")
        assertFalse(s.rewind(userIds()[0]))
        assertEquals(1, launches.size)
    }

    @Test fun onlyMessagesThisSessionSentCanBeRewoundTo() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        assertTrue(s.canRewindTo(userIds()[0]))
        assertFalse(s.canRewindTo("h-0"))
        assertFalse(s.rewind("h-0"))
        s.rewind(userIds()[0])
        assertFalse("the rewound message and those after it are gone, so they can't be rewound to again", s.canRewindTo(userIds()[0]))
    }

    @Test fun rewindingForgetsTheBackgroundAgentsOfTheOldProcess() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        s.send("go")
        conn.emit(Fx.assistantText("m1", "ok", uuid = "a-1"), Fx.toolUse("m1", "t1", "Agent", "{}"), Fx.backgroundTasks("Count"), Fx.toolResult("t1", "started"))
        conn.emit(Fx.result())
        assertEquals(Activity.Agents(1), listener.activities.last())
        assertTrue(s.rewind(userIds()[0]))
        assertNull(listener.activities.last())
    }

    @Test fun aSubagentsAnswerIsNotAnAnchor() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        s.send("two")
        conn.emit(Fx.subagentText("toolu_1", "m9", "sub"), Fx.result())
        s.send("three")
        conn.emit(Fx.result())
        s.rewind(userIds()[2])
        assertEquals("a-1", launches.last().resumeAt)
    }

    // --- folder ---

    @Test fun firstLaunchUsesLaunchDirAndWorktree() {
        val s = session(cwd = "/proj", worktree = "wt-1")
        assertEquals(SessionLaunch(null, null, "/proj", "wt-1"), launches.single())
        assertEquals("/proj", s.cwd)
    }

    @Test fun systemInitCwdReplacesLaunchDirAndRestartDropsWorktree() {
        val s = session(cwd = "/proj", worktree = "wt-1")
        conn.emit(Fx.init("sess-3", "default", cwd = "/proj/.claude/worktrees/wt-1"))
        assertEquals("/proj/.claude/worktrees/wt-1", s.cwd)
        conn.exit(1, "")
        s.restart()
        assertEquals(
            SessionLaunch("sess-3", PermissionMode.DEFAULT, "/proj/.claude/worktrees/wt-1", null),
            launches.last())
    }

    @Test fun blankInitCwdKeepsLaunchDir() {
        val s = session(cwd = "/proj")
        conn.emit("""{"type":"system","subtype":"init","cwd":"","session_id":"s1","permissionMode":"default"}""")
        assertEquals("/proj", s.cwd)
    }

    // --- AskUserQuestion ---

    private fun ask(input: String = Fx.COLOUR_QUESTION, req: String = "q1", tool: String = "tq1") {
        conn.emit(Fx.toolUse("m-$tool", tool, "AskUserQuestion", input), Fx.askQuestion(req, tool, input))
    }

    private fun blue() = mapOf("Which colour do you prefer?" to QuestionAnswer(listOf("Blue"), null))

    @Test fun askUserQuestionBecomesAQuestionItem() {
        val s = session()
        s.send("x")
        ask()
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.AWAITING_PERMISSION, tool.status)
        assertEquals("q1", tool.permissionRequestId)
        val card = tool.question!!
        assertNull(card.answers)
        val q = card.questions.single()
        assertEquals("Colour", q.header)
        assertEquals("Which colour do you prefer?", q.question)
        assertFalse(q.multiSelect)
        assertEquals(listOf("Red", "Blue"), q.options.map { it.label })
        assertEquals("The colour blue", q.options[1].description)
        assertEquals(SessionState.AWAITING_PERMISSION, s.state)
        assertEquals("Which colour do you prefer?", tool.toJson().obj("question")!!.arr("questions")!!.first().jsonObject.str("question"))
    }

    @Test fun answeringAQuestionGoesBackToThinking() {
        val s = session()
        s.send("x")
        ask()
        assertEquals(Activity.Waiting, listener.activities.last())
        assertTrue(s.answer("q1", blue()))
        assertEquals(Activity.Thinking, listener.activities.last())
    }

    @Test fun answerSendsAllowWithQuestionsAndAnswers() {
        val s = session()
        s.send("x")
        ask()
        assertTrue(s.answer("q1", blue()))
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"q1","response":{"behavior":"allow","updatedInput":""" +
                """{"questions":[{"question":"Which colour do you prefer?","header":"Colour","options":[{"label":"Red","description":"The colour red"},""" +
                """{"label":"Blue","description":"The colour blue"}],"multiSelect":false}],"answers":{"Which colour do you prefer?":"Blue"}}}}}""",
            conn.sent.last())
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.RUNNING, tool.status)
        assertNull(tool.permissionRequestId)
        assertEquals(mapOf("Which colour do you prefer?" to "Blue"), tool.question!!.answers)
        assertEquals(SessionState.RUNNING, s.state)
        conn.emit(Fx.toolResult("tq1", "Your questions have been answered: \"Which colour do you prefer?\"=\"Blue\". You can now continue with these answers in mind."))
        val done = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.DONE, done.status)
        assertEquals(mapOf("Which colour do you prefer?" to "Blue"), done.question!!.answers)
    }

    @Test fun multiSelectAndOtherAreJoinedWithCommaSpace() {
        val s = session()
        s.send("x")
        ask(Fx.TWO_QUESTIONS)
        assertTrue(s.answer("q1", mapOf(
            "Which colour?" to QuestionAnswer(emptyList(), "Green"),
            "Which extras?" to QuestionAnswer(listOf("A, B", "C"), "D"))))
        val answers = conn.sentJson().last().obj("response")!!.obj("response")!!.obj("updatedInput")!!.obj("answers")!!
        assertEquals("Green", answers.str("Which colour?"))
        assertEquals("A, B, C, D", answers.str("Which extras?"))
    }

    @Test fun invalidAnswersSendNothingAndKeepTheQuestionPending() {
        val s = session()
        s.send("x")
        ask(Fx.TWO_QUESTIONS)
        val before = conn.sent.size
        val ok = mapOf("Which colour?" to QuestionAnswer(listOf("Red"), null), "Which extras?" to QuestionAnswer(listOf("C"), null))
        assertFalse(s.answer("nope", ok))
        assertFalse(s.answer("q1", ok - "Which extras?"))
        assertFalse(s.answer("q1", ok + ("Another?" to QuestionAnswer(listOf("Red"), null))))
        assertFalse(s.answer("q1", ok + ("Which colour?" to QuestionAnswer(listOf("Purple"), null))))
        assertFalse(s.answer("q1", ok + ("Which colour?" to QuestionAnswer(listOf("Red", "Blue"), null))))
        assertFalse(s.answer("q1", ok + ("Which colour?" to QuestionAnswer(listOf("Red"), "and more"))))
        assertFalse(s.answer("q1", ok + ("Which colour?" to QuestionAnswer(emptyList(), null))))
        assertFalse(s.answer("q1", ok + ("Which extras?" to QuestionAnswer(listOf("C", "C"), null))))
        assertFalse(s.answer("q1", ok + ("Which colour?" to QuestionAnswer(emptyList(), "x".repeat(2001)))))
        assertEquals(before, conn.sent.size)
        assertEquals(ToolStatus.AWAITING_PERMISSION, listener.latest<ChatItem.ToolCall>().status)
        assertTrue(s.answer("q1", ok + ("Which colour?" to QuestionAnswer(emptyList(), "x".repeat(2000)))))
    }

    @Test fun answerIsRejectedForAnOrdinaryPermissionRequest() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        val before = conn.sent.size
        assertFalse(s.answer("r1", emptyMap()))
        assertEquals(before, conn.sent.size)
    }

    @Test fun skipDeniesWithTheSkippedMessage() {
        val s = session()
        s.send("x")
        ask()
        s.respond("q1", PermissionDecision.Deny("The user skipped the question."))
        assertEquals(CliMessages.deny("q1", "The user skipped the question."), conn.sent.last())
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.DENIED, tool.status)
        assertNull(tool.question!!.answers)
    }

    @Test fun aQuestionCannotBeAllowedWithoutAnswers() {
        val s = session()
        s.send("x")
        ask()
        val before = conn.sent.size
        s.respond("q1", PermissionDecision.Allow)
        s.respond("q1", PermissionDecision.AllowForSession)
        assertEquals(before, conn.sent.size)
        assertEquals(ToolStatus.AWAITING_PERMISSION, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun questionIsNeverAutoAllowedAfterAllowForSessionOnAnotherTool() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.AllowForSession)
        val before = conn.sent.size
        ask()
        assertEquals(before, conn.sent.size)
        assertEquals(ToolStatus.AWAITING_PERMISSION, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(SessionState.AWAITING_PERMISSION, s.state)
    }

    @Test fun interruptDeniesAPendingQuestion() {
        val s = session()
        s.send("x")
        ask()
        s.interrupt()
        val sent = conn.sentJson()
        val deny = sent[sent.size - 2].obj("response")!!
        assertEquals("q1", deny.str("request_id"))
        assertEquals("deny", deny.obj("response")!!.str("behavior"))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
        assertFalse(s.answer("q1", blue()))
    }

    @Test fun rejectedAnswerPostsNoticeAndReemitsPendingItem() {
        val s = session()
        s.send("x")
        ask()
        val items = listener.items.size
        assertFalse(s.answer("q1", mapOf("Which colour do you prefer?" to QuestionAnswer(listOf("Purple"), null))))
        val added = listener.items.drop(items)
        assertEquals(2, added.size)
        assertTrue((added[0] as ChatItem.Notice).isError)
        assertEquals("Couldn't send the answer \u2014 please try again or Skip.", (added[0] as ChatItem.Notice).text)
        val tool = added[1] as ChatItem.ToolCall
        assertEquals(ToolStatus.AWAITING_PERMISSION, tool.status)
        assertEquals("q1", tool.permissionRequestId)
    }

    @Test fun malformedQuestionResultShowsDenied() {
        val s = session()
        s.send("x")
        val bad = """{"questions":[{"question":"Q?","header":"H","options":[],"multiSelect":false}]}"""
        conn.emit(Fx.toolUse("m1", "tq1", "AskUserQuestion", bad), Fx.askQuestion("q1", "tq1", bad))
        conn.emit(Fx.toolResult("tq1", "The question was malformed.", isError = true))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun malformedQuestionIsDeniedWithANotice() {
        val s = session()
        s.send("x")
        ask("""{"questions":[{"question":"Q?","header":"H","options":[],"multiSelect":false}]}""")
        assertEquals(CliMessages.deny("q1", "The question was malformed."), conn.sent.last())
        assertTrue(listener.latest<ChatItem.Notice>().isError)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun questionLimitsAreEnforced() {
        fun input(question: String = "Q?", header: String = "H", label: String = "L", description: String = "d", preview: String? = null) =
            """{"questions":[{"question":${Fx.q(question)},"header":${Fx.q(header)},"options":[{"label":${Fx.q(label)},"description":${Fx.q(description)}""" +
                (preview?.let { ""","preview":${Fx.q(it)}""" } ?: "") + """}],"multiSelect":false}]}"""
        fun parses(json: String) = Questions.parse(tether.cli.parseJsonObject(json)!!) != null
        assertTrue(parses(input()))
        assertTrue(parses(input(preview = "x".repeat(Questions.MAX_PREVIEW))))
        assertFalse(parses(input(preview = "x".repeat(Questions.MAX_PREVIEW + 1))))
        assertFalse(parses(input(question = "x".repeat(Questions.MAX_QUESTION + 1))))
        assertFalse(parses(input(header = "x".repeat(Questions.MAX_HEADER + 1))))
        assertFalse(parses(input(label = "x".repeat(Questions.MAX_LABEL + 1))))
        assertFalse(parses(input(description = "x".repeat(Questions.MAX_DESCRIPTION + 1))))
        assertFalse(parses("""{"questions":[]}"""))
        assertFalse(parses("""{}"""))
        val dup = """{"question":"Q?","header":"H","options":[{"label":"L","description":"d"}],"multiSelect":false}"""
        assertFalse(parses("""{"questions":[$dup,$dup]}"""))
    }

    // --- model and effort ---

    private fun answerInit() = conn.emit(Fx.initResponseWithModels(conn.lastRequestId("initialize")))
    private fun answerSettings(model: String = "claude-opus-5-5", effort: String? = "high") =
        conn.emit(Fx.settingsResponse(conn.lastRequestId("get_settings"), model, effort))
    private fun lastRequest() = conn.sentJson().last().obj("request")!!

    @Test fun theModelIsToldOnceTheCliSaysWhatRuns() {
        session()
        assertEquals(0, conn.requestCount("get_settings"))
        answerInit()
        assertEquals(1, conn.requestCount("get_settings"))
        assertTrue(listener.models.isEmpty())
        answerSettings("claude-opus-5-5", "high")
        val choice = listener.models.single()
        assertEquals("default", choice.model)
        assertEquals("high", choice.effort)
        assertEquals(5, choice.options.size)
    }

    @Test fun choosingAModelSetsItAndThenAsksWhatRuns() {
        val s = session()
        answerInit()
        answerSettings()
        s.setModel("sonnet")
        assertEquals("sonnet", s.model)
        assertEquals("set_model", lastRequest().str("subtype"))
        assertEquals("sonnet", lastRequest().str("model"))
        assertEquals("the CLI may answer out of order, so settings wait for the reply", 1, conn.requestCount("get_settings"))
        conn.emit(Fx.modelResponse(conn.lastRequestId("set_model"), "claude-sonnet-5-5"))
        assertEquals(2, conn.requestCount("get_settings"))
        answerSettings("claude-sonnet-5-5", "medium")
        assertEquals("sonnet", listener.models.last().model)
        assertEquals("medium", listener.models.last().effort)
    }

    @Test fun choosingEffortAppliesItAsAFlagSetting() {
        val s = session()
        answerInit()
        answerSettings()
        s.setEffort("low")
        assertEquals("low", s.effort)
        assertEquals("apply_flag_settings", lastRequest().str("subtype"))
        assertEquals("low", lastRequest().obj("settings")!!.str("effortLevel"))
        conn.emit(Fx.modeResponse(conn.lastRequestId("apply_flag_settings"), "default"))
        answerSettings(effort = "low")
        assertEquals("low", listener.models.last().effort)
    }

    @Test fun aRejectedChoiceIsForgottenAndTheHeaderShowsWhatRuns() {
        val s = session(model = "opus", effort = "high")
        answerInit()
        answerSettings()
        s.setModel("nope")
        conn.emit(Fx.errorResponse(conn.lastRequestId("set_model"), "Unknown model"))
        assertEquals("opus", s.model)
        s.setEffort("silly")
        conn.emit(Fx.errorResponse(conn.lastRequestId("apply_flag_settings"), "Invalid effort"))
        assertEquals("high", s.effort)
        assertEquals("both failures ask again, so the pickers go back", 3, conn.requestCount("get_settings"))
        assertTrue(listener.items.filterIsInstance<ChatItem.Notice>().any { "Unknown model" in it.text })
    }

    @Test fun theChoicesCarryIntoEveryNewProcess() {
        val s = session(model = "sonnet", effort = "low")
        assertEquals("sonnet", launches[0].model)
        assertEquals("low", launches[0].effort)
        s.setModel("opus")
        s.setEffort("max")
        conn.exit(1, "boom")
        s.restart()
        assertEquals("opus", launches.last().model)
        assertEquals("max", launches.last().effort)
    }

    @Test fun aNewChatLeavesModelAndEffortToTheCli() {
        session()
        assertNull(launches[0].model)
        assertNull(launches[0].effort)
    }

    @Test fun anOlderCliWithoutTheseRequestsShowsNoPickers() {
        session()
        answerInit()
        conn.emit(Fx.errorResponse(conn.lastRequestId("get_settings"), "Unsupported control request subtype: get_settings"))
        assertTrue(listener.models.isEmpty())
        assertTrue(listener.items.none { it is ChatItem.Notice })
    }

    @Test fun withoutAModelListNothingIsAskedOrTold() {
        val s = session()
        conn.emit(Fx.initResponse(conn.lastRequestId("initialize")))
        assertEquals(0, conn.requestCount("get_settings"))
        s.setModel("sonnet")
        conn.emit(Fx.modelResponse(conn.lastRequestId("set_model"), "claude-sonnet-5-5"))
        assertEquals(0, conn.requestCount("get_settings"))
        assertTrue(listener.models.isEmpty())
    }

    @Test fun aRefusalAnsweredLateDoesNotUndoANewerPick() {
        val s = session(model = "opus")
        answerInit()
        s.setModel("nope")
        val refused = conn.lastRequestId("set_model")
        s.setModel("sonnet")
        conn.emit(Fx.errorResponse(refused, "Unknown model"))
        assertEquals("sonnet", s.model)
    }

    @Test fun pickingWhatIsAlreadyChosenSendsNothing() {
        val s = session(model = "sonnet")
        answerInit()
        s.setModel("sonnet")
        assertEquals(0, conn.requestCount("set_model"))
    }

    @Test fun onlyTheLatestSettingsReplyCounts() {
        val s = session()
        answerInit()
        val first = conn.lastRequestId("get_settings")
        s.setEffort("low")
        conn.emit(Fx.modeResponse(conn.lastRequestId("apply_flag_settings"), "default"))
        conn.emit(Fx.settingsResponse(conn.lastRequestId("get_settings"), effort = "low"))
        conn.emit(Fx.settingsResponse(first, effort = "high"))
        assertEquals(listOf("low"), listener.models.map { it.effort })
    }

    // --- context usage ---

    @Test fun contextUsageIsAskedForOnceInitializeIsAnswered() {
        val s = session()
        assertEquals(0, conn.requestCount("get_context_usage"))
        conn.emit(Fx.initResponse(conn.lastRequestId("initialize")))
        assertEquals(1, conn.requestCount("get_context_usage"))
        conn.emit(Fx.contextResponse(conn.lastRequestId("get_context_usage"), used = 76000, max = 200000, percent = 38))
        val usage = listener.contexts.single()
        assertEquals(76000L, usage.used)
        assertEquals(38, usage.percent)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun contextUsageIsAskedForAfterEveryTurn() {
        val s = session()
        s.send("go")
        conn.emit(Fx.result())
        assertEquals(1, conn.requestCount("get_context_usage"))
        s.send("again")
        conn.emit(Fx.result())
        assertEquals(2, conn.requestCount("get_context_usage"))
    }

    @Test fun aFailedContextRequestIsNotShown() {
        val s = session()
        s.send("go")
        conn.emit(Fx.result())
        conn.emit(Fx.errorResponse(conn.lastRequestId("get_context_usage"), "Unsupported control request subtype"))
        assertTrue(listener.contexts.isEmpty())
        assertTrue(listener.current().none { it is ChatItem.Notice })
    }

    @Test fun aFailedInitializeAsksForNothing() {
        session()
        conn.emit(Fx.errorResponse(conn.lastRequestId("initialize"), "nope"))
        assertEquals(0, conn.requestCount("get_context_usage"))
    }

    @Test fun anUnparsableContextReplyIsIgnored() {
        val s = session()
        s.send("go")
        conn.emit(Fx.result())
        conn.emit(Fx.modeResponse(conn.lastRequestId("get_context_usage"), "plan"))
        assertTrue(listener.contexts.isEmpty())
        assertEquals("the reply is not mistaken for a mode change", null, s.mode)
    }

    @Test fun aStaleContextReplyIsDropped() {
        val s = session()
        s.send("go")
        conn.emit(Fx.result())
        val first = conn.lastRequestId("get_context_usage")
        s.send("again")
        conn.emit(Fx.result())
        conn.emit(Fx.contextResponse(first, percent = 10))
        conn.emit(Fx.contextResponse(conn.lastRequestId("get_context_usage"), percent = 20))
        conn.emit(Fx.errorResponse(first, "late"))
        assertEquals(listOf(20), listener.contexts.map { it.percent })
        assertTrue(listener.current().none { it is ChatItem.Notice })
    }
}
