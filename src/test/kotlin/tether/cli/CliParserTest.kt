package tether.cli

import kotlinx.serialization.json.jsonObject
import tether.testutil.Fx
import tether.testutil.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliParserTest {
    private fun one(line: String): CliEvent = CliParser.parse(line).single()

    /** The events of an assistant line after its [CliEvent.AssistantMessage]. */
    private fun blocks(line: String): List<CliEvent> = CliParser.parse(line).filterNot { it is CliEvent.AssistantMessage }

    @Test fun systemInit() {
        assertEquals(CliEvent.SystemInit("sess-1", "acceptEdits", "/proj"), one(Fx.init(mode = "acceptEdits")))
        assertEquals(
            CliEvent.SystemInit("sess-2", "default", "/proj/.claude/worktrees/wt-1"),
            one(Fx.init("sess-2", cwd = "/proj/.claude/worktrees/wt-1")))
    }

    @Test fun systemInitWithoutCwd() {
        assertEquals(CliEvent.SystemInit("s", null, null), one("""{"type":"system","subtype":"init","session_id":"s"}"""))
    }

    @Test fun otherSystemMessagesAreIgnored() {
        assertEquals(CliEvent.Ignored("system/hook_started"), one(Fx.hookStarted()))
        assertEquals(CliEvent.Ignored("rate_limit_event"), one(Fx.rateLimit()))
        assertEquals(CliEvent.Ignored("stream_event/subagent"), one(Fx.subagentMessageStart("m1", "toolu_1")))
    }

    @Test fun promptSuggestion() {
        assertEquals(CliEvent.PromptSuggestion("make it a one-liner"), one(Fx.promptSuggestion("make it a one-liner")))
        assertEquals(CliEvent.PromptSuggestion(""), one("""{"type":"prompt_suggestion"}"""))
    }

    @Test fun textDeltaAndBlockStop() {
        assertEquals(CliEvent.TextDelta("m1", 1, "DO"), one(Fx.textDelta("m1", 1, "DO")))
        assertEquals(CliEvent.BlockStop("m1", 1), one(Fx.blockStop("m1", 1)))
    }

    @Test fun subagentEventsCarryTheirParent() {
        assertEquals(CliEvent.ToolUse("t2", "Bash", parseJsonObject("""{"command":"ls"}""")!!, "t1"),
            one(Fx.subagentToolUse("t1", "t2", "Bash", """{"command":"ls"}""")))
        assertEquals(CliEvent.AssistantText("sm1", "hi", "t1"), one(Fx.subagentText("t1", "sm1", "hi")))
        assertEquals("t1", (one(Fx.subagentToolResult("t1", "t2", "out")) as CliEvent.ToolResult).parentId)
        assertTrue(CliParser.parse(Fx.subagentPrompt("t1", "Do it")).isEmpty())
        assertEquals(CliEvent.Ignored("stream_event/subagent"), one(Fx.subagentTextDelta("t1", "sm1", "x")))
        assertNull((blocks(Fx.toolUse("m1", "t1", "Agent", "{}")).single() as CliEvent.ToolUse).parentId)
    }

    @Test fun taskEventsAreParsed() {
        assertEquals(CliEvent.TaskStarted("t1", "general-purpose", true, "Count", "a1", "local_agent"), one(Fx.taskStarted("t1", true, "Count")))
        assertEquals(CliEvent.TaskProgress("t1", 7, 42000, "Bash"), one(Fx.taskProgress("t1", 7, 42000, "Bash")))
        assertEquals(CliEvent.TaskDone("t1", "completed", 3, 4063, "DONE"), one(Fx.taskNotification("t1")))
        assertEquals(CliEvent.TaskDone("t1", "failed", 3, 4063, null), one(Fx.taskNotification("t1", "failed", null)))
        assertEquals(CliEvent.Ignored("system/task_started"), one(Fx.taskStarted("t1").replace(""""tool_use_id":"t1",""", "")))
    }

    @Test fun aTopLevelMessageStartIsParsed() {
        assertEquals(CliEvent.MessageStart("m1"), one(Fx.messageStart("m1")))
    }

    @Test fun backgroundTasksAreCounted() {
        assertEquals(
            CliEvent.BackgroundTasks(listOf(BackgroundTask("a0", "local_agent", "Count"), BackgroundTask("a1", "local_agent", "Search"))),
            one(Fx.backgroundTasks("Count", "Search")),
        )
        assertEquals(CliEvent.BackgroundTasks(emptyList()), one(Fx.backgroundTasks()))
    }

    @Test fun thinkingStartIsParsedAndItsDeltasAreIgnored() {
        assertEquals(CliEvent.ThinkingStart("m1", 0), one(Fx.thinkingStart("m1", 0)))
        assertEquals(CliEvent.Ignored("stream_event/content_block_delta/thinking_delta"), one(Fx.thinkingDelta("m1", 0, "hmm")))
    }

    @Test fun aMainThreadAssistantLineReportsItsUuidFirst() {
        assertEquals(
            listOf(CliEvent.AssistantMessage("a-1"), CliEvent.AssistantText("m1", "DONE")),
            CliParser.parse(Fx.assistantText("m1", "DONE", uuid = "a-1")))
        assertEquals("a line with only thinking shows nothing, but it is still a message a rewind can resume at",
            listOf(CliEvent.AssistantMessage("a-2")), CliParser.parse(Fx.assistantThinking("m1", uuid = "a-2")))
    }

    /** Subagent messages live in their own transcript; the main conversation can't be resumed at them. */
    @Test fun aSubagentsAssistantLineReportsNoUuid() {
        assertTrue(CliParser.parse(Fx.subagentText("toolu_1", "m2", "hi")).none { it is CliEvent.AssistantMessage })
    }

    @Test fun assistantTextAndToolUse() {
        assertEquals(CliEvent.AssistantText("m1", "DONE"), blocks(Fx.assistantText("m1", "DONE")).single())
        val tool = blocks(Fx.toolUse("m1", "t1", "Write", """{"file_path":"/p/a.txt","content":"hi"}""")).single() as CliEvent.ToolUse
        assertEquals("t1", tool.toolUseId)
        assertEquals("Write", tool.name)
        assertEquals("/p/a.txt", tool.input.str("file_path"))
    }

    @Test fun aLocalCommandsOutputIsMarkedAsCommandOutput() {
        assertEquals(CliEvent.AssistantText("m1", "a\n  b", commandOutput = true), blocks(Fx.commandOutput("m1", "a\n  b")).single())
    }

    @Test fun thinkingBlocksProduceNoEvents() {
        assertTrue(blocks(Fx.assistantThinking("m1")).isEmpty())
    }

    @Test fun permissionRequest() {
        val ev = one(Fx.canUseTool("r1", "t1", "Write", """{"file_path":"/p/a.txt","content":"hi"}""")) as CliEvent.PermissionRequest
        assertEquals("r1", ev.requestId)
        assertEquals("Write", ev.toolName)
        assertEquals("t1", ev.toolUseId)
        assertEquals("hi", ev.input.str("content"))
    }

    @Test fun mcpMessage() {
        val ev = one(Fx.mcpMessage("r1", "tether", """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")) as CliEvent.McpMessage
        assertEquals("r1", ev.requestId)
        assertEquals("tether", ev.server)
        assertEquals("tools/list", ev.message.str("method"))
    }

    @Test fun hookCallback() {
        val ev = one(Fx.hookCallback("r1", "after-edit", "Edit", "/p/a.ts")) as CliEvent.HookCallback
        assertEquals("r1", ev.requestId)
        assertEquals("after-edit", ev.callbackId)
        assertEquals("/p/a.ts", ev.input.obj("tool_input")!!.str("file_path"))
    }

    @Test fun askUserQuestionRequestIsAPermissionRequestCarryingTheQuestions() {
        val ev = one(Fx.askQuestion("r1", "t1")) as CliEvent.PermissionRequest
        assertEquals("AskUserQuestion", ev.toolName)
        assertEquals("t1", ev.toolUseId)
        assertEquals("Which colour do you prefer?", ev.input.arr("questions")!!.first().jsonObject.str("question"))
    }

    @Test fun toolResultWithOriginalFile() {
        val ev = one(Fx.toolResult("t1", "ok", toolUseResultJson = Fx.editResult("/p/f.txt", "line two\n"))) as CliEvent.ToolResult
        assertEquals(CliEvent.ToolResult("t1", false, "ok", "/p/f.txt", "line two\n", true), ev)
    }

    @Test fun toolResultForCreatedFileHasNullOriginal() {
        val ev = one(Fx.toolResult("t1", "ok", toolUseResultJson = Fx.createResult("/p/g.txt"))) as CliEvent.ToolResult
        assertEquals(CliEvent.ToolResult("t1", false, "ok", "/p/g.txt", null, true), ev)
    }

    @Test fun toolResultErrorWithoutToolUseResult() {
        val ev = one(Fx.toolResult("t1", "denied", isError = true)) as CliEvent.ToolResult
        assertEquals(CliEvent.ToolResult("t1", true, "denied", null, null, false), ev)
    }

    @Test fun toolResultContentAsTextBlocks() {
        val line = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]}}"""
        assertEquals("a\nb", (one(line) as CliEvent.ToolResult).content)
    }

    @Test fun interruptedUserText() {
        assertEquals(CliEvent.UserText("[Request interrupted by user]"), one(Fx.interruptedUser()))
    }

    @Test fun controlResponses() {
        val ok = one(Fx.initResponse("init-1", "plan")) as CliEvent.ControlResponse
        assertEquals("init-1", ok.requestId)
        assertTrue(ok.isSuccess)
        assertEquals("plan", ok.response!!.str("current_permission_mode"))
        assertEquals(CliEvent.ControlResponse("x", false, null, "nope"), one(Fx.errorResponse("x", "nope")))
    }

    @Test fun results() {
        assertEquals(CliEvent.Result(false, "success", "DONE", 5566, 0.1944822, emptyList()), one(Fx.result()))
        val err = one(Fx.errorResult()) as CliEvent.Result
        assertTrue(err.isError)
        assertEquals("error_during_execution", err.subtype)
        assertEquals(listOf("[ede_diagnostic] result_type=user"), err.errors)
    }

    @Test fun malformedAndUnknown() {
        assertEquals(CliEvent.Malformed("not json"), one("not json"))
        assertEquals(CliEvent.Ignored("mystery"), one("""{"type":"mystery"}"""))
    }

    @Test fun readOfAnImageCarriesItsImageBlock() {
        val ev = one(Fx.readImageResult("t1", TestImages.BLUE_PNG_BASE64)) as CliEvent.ToolResult
        assertEquals(
            CliEvent.ToolResult("t1", false, "", null, null, false, listOf(ImageBlock("image/png", TestImages.BLUE_PNG_BASE64))),
            ev)
    }

    @Test fun nonBase64AndMalformedImageBlocksAreSkipped() {
        val line = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":[{"type":"text","text":"see"},{"type":"image","source":{"type":"url","url":"https://x/y.png"}},{"type":"image"},{"type":"image","source":{"type":"base64","data":"AAAA"}}]}]}}"""
        val ev = one(line) as CliEvent.ToolResult
        assertEquals("see", ev.content)
        assertEquals(listOf(ImageBlock(null, "AAAA")), ev.images)
    }
}
