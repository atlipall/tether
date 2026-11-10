package tether.acp

import tether.cli.arr
import tether.cli.long
import tether.cli.obj
import tether.cli.str
import tether.session.Choice
import tether.testutil.FakeConnection
import tether.testutil.TestImages
import tether.image.Image
import tether.image.ImageType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class JsonAcpClientTest {
    private val conn = FakeConnection()
    private val client = JsonAcpClient(conn) { it.run() }
    private val events = mutableListOf<String>()
    private val updates = mutableListOf<AcpUpdate>()
    private val permissions = mutableListOf<PermissionRequest>()

    private val listener = object : AcpListener {
        override fun onUpdate(sessionId: String, update: AcpUpdate) { updates += update; events += "update:$sessionId" }
        override fun onPermission(request: PermissionRequest) { permissions += request; events += "permission" }
        override fun onClosed(exitCode: Int?, detail: String) { events += "closed:$exitCode:$detail" }
    }

    private fun lastRequest(method: String): JsonObject = conn.sentJson().last { it.str("method") == method }
    private fun idOf(method: String): Long = lastRequest(method).long("id")!!
    private fun reply(id: Long, result: String) = conn.emit("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    private fun update(json: String) = conn.emit("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$json}}""")
    private fun <T> failure(future: CompletableFuture<T>): Throwable = try { future.get(); throw AssertionError("expected a failure") } catch (e: ExecutionException) { e.cause!! }

    private fun connected(): AgentInfo {
        val info = client.connect(listener)
        reply(idOf("initialize"), """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"promptCapabilities":{"image":true}}}""")
        return info.get()
    }

    @Test fun initializeAdvertisesNoClientCapabilitiesAndReadsTheAgentsOnes() {
        val info = connected()

        val params = lastRequest("initialize").obj("params")!!
        assertEquals(1L, params.long("protocolVersion"))
        assertEquals(JsonObject(emptyMap()), params.obj("clientCapabilities"))
        assertEquals("2.0", lastRequest("initialize").str("jsonrpc"))
        assertEquals(AgentInfo(loadSession = true, images = true), info)
    }

    @Test fun anotherProtocolVersionFailsTheConnect() {
        val info = client.connect(listener)

        reply(idOf("initialize"), """{"protocolVersion":2}""")

        assertTrue(failure(info).message!!.contains("protocol version 2"))
    }

    @Test fun repliesAreMatchedToTheirRequestsById() {
        connected()
        val first = client.newSession("/a")
        val second = client.newSession("/b")
        val ids = conn.sentJson().filter { it.str("method") == "session/new" }.map { it.long("id")!! }

        reply(ids[1], """{"sessionId":"s-b"}""")
        reply(ids[0], """{"sessionId":"s-a","modes":{"currentModeId":"default","availableModes":[{"id":"default","name":"Ask"},{"id":"dont_ask","name":"Don't ask"}]}}""")

        assertEquals(SessionOpened("s-a", listOf(Choice("default", "Ask"), Choice("dont_ask", "Don't ask")), "default"), first.get())
        assertEquals(SessionOpened("s-b", emptyList(), null), second.get())
        assertEquals(listOf("/a", "/b"), conn.sentJson().filter { it.str("method") == "session/new" }.map { it.obj("params")!!.str("cwd") })
        assertEquals(0, lastRequest("session/new").obj("params")!!.arr("mcpServers")!!.size)
    }

    @Test fun promptSendsTextAndImageBlocksAndCompletesWithTheStopReason() {
        connected()
        val image = Image(ImageType.PNG, TestImages.bytes(TestImages.RED_PNG_BASE64), 4, 4)

        val turn = client.prompt("s1", "hello", listOf(image))
        reply(idOf("session/prompt"), """{"stopReason":"end_turn"}""")

        val blocks = lastRequest("session/prompt").obj("params")!!.arr("prompt")!!.map { it.jsonObject }
        assertEquals(listOf("text", "image"), blocks.map { it.str("type") })
        assertEquals("hello", blocks[0].str("text"))
        assertEquals("image/png", blocks[1].str("mimeType"))
        assertEquals(image.base64(), blocks[1].str("data"))
        assertEquals("end_turn", turn.get())
    }

    @Test fun updatesArriveInWireOrderBeforeThePromptCompletes() {
        connected()
        val turn = client.prompt("s1", "hi", emptyList())
        turn.whenComplete { stop, _ -> events += "done:$stop" }

        update("""{"sessionUpdate":"agent_message_chunk","messageId":"m1","content":{"type":"text","text":"Hel"}}""")
        update("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hmm"}}""")
        update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read a.txt","kind":"read","status":"pending","rawInput":{"path":"a.txt"},"locations":[{"path":"/p/a.txt"}]}""")
        update("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"line 1"}}]}""")
        update("""{"sessionUpdate":"current_mode_update","currentModeId":"dont_ask"}""")
        update("""{"sessionUpdate":"usage_update","used":1200,"size":200000}""")
        update("""{"sessionUpdate":"plan","entries":[]}""")
        reply(idOf("session/prompt"), """{"stopReason":"end_turn"}""")

        assertEquals(
            listOf(
                AcpUpdate.AgentText("m1", "Hel"),
                AcpUpdate.Thought,
                AcpUpdate.Tool("t1", "Read a.txt", "pending", JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive("a.txt"))), "/p/a.txt", null),
                AcpUpdate.Tool("t1", null, "completed", null, null, "line 1"),
                AcpUpdate.Mode("dont_ask"),
                AcpUpdate.Usage(1200, 200000),
                AcpUpdate.Other("plan"),
            ),
            updates,
        )
        assertEquals(List(7) { "update:s1" } + "done:end_turn", events)
    }

    @Test fun availableCommandsArriveAsAnUpdate() {
        connected()
        update("""{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"web","description":"Search the web","input":{"hint":"query"}}]}""")
        assertEquals(listOf(AcpUpdate.Commands(listOf(tether.cli.SlashCommand("web", "Search the web", "query")))), updates)
    }

    @Test fun permissionIsAnsweredWithTheSelectedOptionOnce() {
        connected()

        conn.emit("""{"jsonrpc":"2.0","id":"p-7","method":"session/request_permission","params":{"sessionId":"s1",
            "toolCall":{"toolCallId":"edit-approval-1","title":"Approve edit: a.txt","kind":"edit","status":"pending",
            "content":[{"type":"diff","path":"/p/a.txt","oldText":"a","newText":"b"}]},
            "options":[{"optionId":"allow_once","name":"Allow edit","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}""".replace("\n", ""))
        val request = permissions.single()
        request.select("allow_once")
        request.select("deny")
        request.cancel()

        assertEquals("edit-approval-1", request.tool.id)
        assertEquals("Approve edit: a.txt", request.tool.title)
        assertEquals("/p/a.txt", request.tool.path)
        assertEquals(listOf(PermissionOption("allow_once", "Allow edit", "allow_once"), PermissionOption("deny", "Deny", "reject_once")), request.options)
        val answers = conn.sentJson().filter { it.str("id") == "p-7" }
        assertEquals(1, answers.size)
        assertEquals("""{"outcome":"selected","optionId":"allow_once"}""", answers.single().obj("result")!!.obj("outcome").toString())
    }

    @Test fun cancelledPermissionAnswersCancelled() {
        connected()
        conn.emit("""{"jsonrpc":"2.0","id":4,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"perm-check-1"},"options":[]}}""")

        permissions.single().cancel()

        assertEquals("""{"outcome":"cancelled"}""", conn.sentJson().last().obj("result")!!.obj("outcome").toString())
    }

    @Test fun unsupportedAgentRequestsGetMethodNotFound() {
        connected()

        conn.emit("""{"jsonrpc":"2.0","id":9,"method":"fs/read_text_file","params":{"path":"/etc/passwd"}}""")

        val answer = conn.sentJson().last()
        assertEquals(9L, answer.long("id"))
        assertEquals(-32601L, answer.obj("error")!!.long("code"))
        assertTrue(permissions.isEmpty())
    }

    @Test fun agentErrorFailsOnlyThatRequest() {
        connected()
        val turn = client.prompt("s1", "hi", emptyList())

        conn.emit("""{"jsonrpc":"2.0","id":${idOf("session/prompt")},"error":{"code":-32603,"message":"admitted_turn_failed"}}""")

        val error = failure(turn)
        assertTrue(error is AgentError)
        assertEquals(-32603, (error as AgentError).code)
        assertEquals("admitted_turn_failed", error.message)
    }

    @Test fun cancelAndSetModeUseTheSession() {
        connected()

        client.cancel("s1")
        val mode = client.setMode("s1", "accept_edits")
        reply(idOf("session/set_mode"), "null")

        val cancel = lastRequest("session/cancel")
        assertNull(cancel["id"])
        assertEquals("s1", cancel.obj("params")!!.str("sessionId"))
        assertEquals("accept_edits", lastRequest("session/set_mode").obj("params")!!.str("modeId"))
        mode.get()
    }

    @Test fun exitFailsOpenRequestsBeforeOnClosedAndLaterCallsAtOnce() {
        connected()
        val turn = client.prompt("s1", "hi", emptyList())
        turn.whenComplete { _, e -> events += "turn:${e?.cause?.javaClass?.simpleName ?: e?.javaClass?.simpleName}" }

        conn.exit(1, "boom")
        val later = client.newSession("/a")

        assertEquals(listOf("turn:ConnectionLost", "closed:1:boom"), events)
        assertTrue(failure(turn) is ConnectionLost)
        assertTrue(failure(later) is ConnectionLost)
        assertEquals(1, conn.sentJson().count { it.str("method") == "session/prompt" })
        assertFalse(conn.sentJson().any { it.str("method") == "session/new" })
    }

    @Test fun startFailureFailsTheConnect() {
        val broken = object : tether.cli.CliConnection {
            override fun start(onLine: (String) -> Unit, onExit: (Int, String) -> Unit) = throw java.io.IOException("Cannot run program \"hermes\"")
            override fun send(line: String) {}
            override fun stop() {}
        }

        val info = JsonAcpClient(broken) { it.run() }.connect(listener)

        assertEquals("Cannot run program \"hermes\"", failure(info).message)
    }

    @Test(timeout = 10_000) fun connectReturnsWhileTheAgentProcessIsStillStarting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var startThread: Thread? = null
        val slow = object : tether.cli.CliConnection {
            override fun start(onLine: (String) -> Unit, onExit: (Int, String) -> Unit) {
                startThread = Thread.currentThread()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            override fun send(line: String) {}
            override fun stop() {}
        }

        val info = JsonAcpClient(slow).connect(listener)
        val startedElsewhere = entered.await(5, TimeUnit.SECONDS)
        val doneWhileStarting = info.isDone
        release.countDown()

        assertTrue(startedElsewhere)
        assertTrue(startThread !== Thread.currentThread())
        assertFalse(doneWhileStarting)
    }

    @Test fun closeBeforeTheScheduledStartStartsNoProcess() {
        val scheduled = mutableListOf<Runnable>()
        val lazy = JsonAcpClient(conn) { scheduled += it }
        val info = lazy.connect(listener)

        lazy.close()
        scheduled.single().run()

        assertFalse(conn.started)
        assertTrue(conn.sent.isEmpty())
        assertTrue(failure(info) is ConnectionLost)
    }

    @Test fun closeStopsTheConnection() {
        connected()

        client.close()

        assertTrue(conn.stopped)
    }
}
