package tether.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cwd = "/Users/me/my_proj.v2"

    private fun write(store: SessionStore, id: String, modified: Long, vararg lines: String): File =
        writeIn(store, cwd, id, modified, *lines)

    /** Writes a transcript of [lines] without a trailing newline. */
    private fun writeIn(store: SessionStore, folder: String, id: String, modified: Long, vararg lines: String): File {
        val dir = store.projectDir(folder).apply { mkdirs() }
        return File(dir, "$id.jsonl").apply { writeText(lines.joinToString("\n")); setLastModified(modified) }
    }

    private fun user(text: String) = """{"type":"user","message":{"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(text)}},"sessionId":"s"}"""
    private fun meta(text: String) = """{"type":"user","isMeta":true,"message":{"role":"user","content":"$text"}}"""
    private fun assistant(text: String) = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""
    /** A user line as claude 2.1.317 writes it for a message with an image: text block, image block (data trimmed). */
    private fun userWithImages(text: String?, images: Int): String {
        val blocks = listOfNotNull(text?.let { """{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(it)}}""" }) +
            List(images) { """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}""" }
        return """{"type":"user","message":{"role":"user","content":[${blocks.joinToString(",")}]},"imagePasteIds":[1],"sessionId":"s"}"""
    }
    private fun imageCompanion() = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Image: source: /tmp/s/images/1.png]"}]},"isMeta":true}"""
    private val readImageResult = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t","type":"tool_result","content":[{"type":"image","source":{"type":"base64","data":"iVBORw0KGgo=","media_type":"image/png"}}]}]}}"""
    private fun localCommand(content: String) =
        """{"type":"system","subtype":"local_command","content":${kotlinx.serialization.json.JsonPrimitive(content)},"isSidechain":false,"sessionId":"s"}"""
    private val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"x"}]}}"""

    @Test fun projectDirEncodesCwd() {
        val store = SessionStore(tmp.root)
        assertEquals(File(tmp.root, "projects/-Users-me-my-proj-v2"), store.projectDir(cwd))
    }

    @Test fun listsNewestFirstWithFirstRealUserMessageAsTitle() {
        val store = SessionStore(tmp.root)
        write(store, "old", 1_000_000, """{"type":"session-owner"}""", user("first question"))
        write(store, "new", 2_000_000, meta("<command-name>"), user("<local-command>"), user("second\nquestion"))
        write(store, "empty", 3_000_000, """{"type":"session-owner"}""")
        assertEquals(
            listOf(SessionSummary("new", "second question", 2_000_000, cwd), SessionSummary("old", "first question", 1_000_000, cwd)),
            store.list(cwd))
    }

    @Test fun listReadsTheLastCustomTitle() {
        val store = SessionStore(tmp.root)
        write(store, "s", 1_000_000, user("first"),
            """{"type":"custom-title","customTitle":"Old name","sessionId":"s"}""", user("mentions custom-title"),
            """{"type":"custom-title","customTitle":"New  name","sessionId":"s"}""")
        assertEquals(SessionSummary("s", "first", 1_000_000, cwd, customTitle = "New name"), store.list(cwd).single())
    }

    @Test fun blankCustomTitleMeansNone() {
        val store = SessionStore(tmp.root)
        write(store, "s", 1, user("first"), """{"type":"custom-title","customTitle":"Named","sessionId":"s"}""",
            """{"type":"custom-title","customTitle":"","sessionId":"s"}""")
        assertEquals(null, store.list(cwd).single().customTitle)
    }

    @Test fun saveTitleAppendsALineTheListReads() {
        val store = SessionStore(tmp.root)
        val file = write(store, "s", 1, user("first"))
        store.saveTitle(cwd, "s", "Login \"fix\"")
        store.saveTitle(cwd, "s", "Login fix 2")
        val lines = file.readLines()
        assertEquals(3, lines.size)
        assertEquals("""{"type":"custom-title","customTitle":"Login \"fix\"","sessionId":"s"}""", lines[1])
        assertEquals("Login fix 2", store.list(cwd).single().customTitle)
        assertEquals(listOf(ChatItem.UserText("h-0", "first")), store.read(cwd, "s").items)
    }

    @Test fun archivedSessionsAreListedOnlyOnRequest() {
        val store = SessionStore(tmp.root)
        write(store, "a", 2_000_000, user("old work"), """{"type":"custom-title","customTitle":"[archived] Login fix","sessionId":"a"}""")
        write(store, "b", 1_000_000, user("current"))
        assertEquals(listOf("b"), store.list(cwd).map { it.id })
        assertEquals(SessionSummary("a", "old work", 2_000_000, cwd, "Login fix", archived = true), store.list(cwd, archived = true).single())
        assertEquals(listOf("a"), store.listAll(listOf(cwd), archived = true).map { it.id })
    }

    @Test fun archiveAndUnarchiveKeepTheName() {
        val store = SessionStore(tmp.root)
        val file = write(store, "s", 1, user("first"))
        store.saveTitle(cwd, "s", "Login fix", archived = true)
        assertTrue(file.readLines().last().contains("\"customTitle\":\"[archived] Login fix\""))
        assertEquals("Login fix", store.list(cwd, archived = true).single().customTitle)
        store.saveTitle(cwd, "s", "Login fix")
        assertEquals(SessionSummary("s", "first", file.lastModified(), cwd, "Login fix"), store.list(cwd).single())
    }

    @Test fun archivingAnUnnamedSessionWritesTheBareMarker() {
        val store = SessionStore(tmp.root)
        val file = write(store, "s", 1, user("first"))
        store.saveTitle(cwd, "s", "", archived = true)
        assertTrue(file.readLines().last().contains("\"customTitle\":\"[archived]\""))
        assertEquals(null, store.list(cwd, archived = true).single().customTitle)
    }

    @Test fun sessionWithATitleButNoMessageIsListedUnderItsTitle() {
        val store = SessionStore(tmp.root)
        write(store, "s", 1, """{"type":"custom-title","customTitle":"[archived] Login fix","sessionId":"s"}""")
        assertEquals(SessionSummary("s", "Login fix", 1, cwd, "Login fix", archived = true), store.list(cwd, archived = true).single())
    }

    @Test fun listSeesATitleAddedAfterTheFirstRead() {
        val store = SessionStore(tmp.root)
        val file = write(store, "s", 1, user("first"))
        assertEquals(null, store.list(cwd).single().customTitle)
        store.saveTitle(cwd, "s", "Login fix")
        assertEquals("Login fix", store.list(cwd).single().customTitle)
    }

    @Test fun listOfUnknownProjectIsEmpty() = assertEquals(emptyList<SessionSummary>(), SessionStore(tmp.root).list("/nowhere"))

    @Test fun transcriptKeepsUserAndAssistantTextAndStripsIdeNote() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1, user("hello"), assistant("hi there"), toolResult, user("[IDE] Since your last turn...\nRe-read.\n\nnext one"), assistant("ok"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "hello"),
                ChatItem.AssistantText("h-1", "hi there", false),
                ChatItem.UserText("h-2", "next one"),
                ChatItem.AssistantText("h-3", "ok", false),
            ),
            store.read(cwd, "s1").items)
    }

    @Test fun transcriptShowsLocalCommandsAndTheirOutput() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1,
            user("hello"),
            localCommand("<command-name>/model</command-name>\n            <command-message>model</command-message>\n            <command-args>opus</command-args>"),
            localCommand("<local-command-stdout>Set model to Opus\n  (default)</local-command-stdout>"),
            localCommand("/plugin list"),
            localCommand("<local-command-stdout></local-command-stdout>"),
            assistant("ok"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "hello"),
                ChatItem.UserText("h-1", "/model opus"),
                ChatItem.AssistantText("h-2", "Set model to Opus\n  (default)", false, commandOutput = true),
                ChatItem.UserText("h-3", "/plugin list"),
                ChatItem.AssistantText("h-4", "ok", false),
            ),
            store.read(cwd, "s1").items)
    }

    @Test fun transcriptStripsEveryIdeNote() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1,
            user("[IDE] Since your last turn...\nRe-read.\n\n[IDE] The user is looking at `a.ts`.\n\nboth"),
            assistant("a"),
            user("[IDE] The user is looking at `a.ts`.\n\nctx only"),
            assistant("b"),
            user("[IDE] Since your last turn...\n\n[IDE] The user is looking at `a.ts`."),
            assistant("c"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "both"),
                ChatItem.AssistantText("h-1", "a", false),
                ChatItem.UserText("h-2", "ctx only"),
                ChatItem.AssistantText("h-3", "b", false),
                ChatItem.AssistantText("h-4", "c", false),
            ),
            store.read(cwd, "s1").items)
    }

    @Test fun listTitleUsesTheCleanedText() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1_000_000, user("[IDE] edit\n\n[IDE] ctx\n\nreal question"))
        assertEquals("real question", store.list(cwd).single().title)
    }

    @Test fun listAllMergesFoldersNewestFirst() {
        val store = SessionStore(tmp.root)
        val wt = "$cwd/.claude/worktrees/wt-1"
        writeIn(store, cwd, "a", 1_000_000, user("in project"))
        writeIn(store, wt, "b", 3_000_000, user("in worktree"))
        writeIn(store, cwd, "c", 2_000_000, user("later in project"))
        assertEquals(
            listOf(
                SessionSummary("b", "in worktree", 3_000_000, wt),
                SessionSummary("c", "later in project", 2_000_000, cwd),
                SessionSummary("a", "in project", 1_000_000, cwd),
            ),
            store.listAll(listOf(cwd, wt, cwd, "/nowhere")))
    }

    @Test fun listAllHonoursLimit() {
        val store = SessionStore(tmp.root)
        writeIn(store, cwd, "a", 1_000_000, user("one"))
        writeIn(store, "$cwd/.claude/worktrees/wt-1", "b", 2_000_000, user("two"))
        assertEquals(listOf("b"), store.listAll(listOf(cwd, "$cwd/.claude/worktrees/wt-1"), limit = 1).map { it.id })
    }

    @Test fun transcriptShowsImagePlaceholdersAboveTheText() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1,
            userWithImages("[IDE] The user is looking at a.ts.\n\nwhat is this?", 2), imageCompanion(), assistant("a chart"),
            userWithImages(null, 1), imageCompanion(), assistant("ok"),
            userWithImages("[IDE] The user is looking at a.ts.", 1), readImageResult, assistant("b"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "[image] [image]\nwhat is this?"),
                ChatItem.AssistantText("h-1", "a chart", false),
                ChatItem.UserText("h-2", "[image]"),
                ChatItem.AssistantText("h-3", "ok", false),
                ChatItem.UserText("h-4", "[image]"),
                ChatItem.AssistantText("h-5", "b", false),
            ),
            store.read(cwd, "s1").items)
    }

    @Test fun listTitleOfAnImageMessage() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1_000_000, userWithImages("look", 1))
        assertEquals("[image] look", store.list(cwd).single().title)
    }

    @Test fun startedAtIsTheFirstTimestampInTheTranscript() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1_000_000,
            """{"type":"mode","mode":"normal"}""",
            "not json",
            """{"type":"attachment","timestamp":"2026-11-03T09:34:21.196Z"}""",
            """{"type":"user","timestamp":"2026-11-03T09:35:41.577Z","message":{"role":"user","content":"hi"}}""",
        )
        assertEquals(java.time.Instant.parse("2026-11-03T09:34:21.196Z").toEpochMilli(), store.read(cwd, "s1").startedAt)
    }

    @Test fun startedAtIsNullWithoutTranscriptOrTimestamp() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1_000_000, user("hi"), """{"type":"user","timestamp":"yesterday"}""")
        assertEquals(null, store.read(cwd, "s1").startedAt)
        assertEquals(null, store.read(cwd, "missing").startedAt)
    }

    @Test fun lastAssistantUuidIsTheNewestMainConversationAnswer() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1000,
            user("hi"),
            """{"type":"assistant","uuid":"a-1","message":{"role":"assistant","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"assistant","uuid":"a-2","message":{"role":"assistant","content":[{"type":"text","text":"two"}]}}""",
            """{"type":"assistant","uuid":"side","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"sub"}]}}""",
            """{"type":"custom-title","customTitle":"x"}""",
        )
        assertEquals("a-2", store.read(cwd, "s1").lastAssistantUuid)
        assertEquals(null, store.read(cwd, "missing").lastAssistantUuid)
    }

    @Test fun readSkipsUnusedLinesWithoutLosingMessagesTitlesOrTheAnchor() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1000,
            """{"type":"user","timestamp":"2026-11-03T09:34:21.196Z","message":{"role":"user","content":"fix it"}}""",
            """{"type":"progress","data":{"type":"user","note":"not a message"}}""",
            """{"type":"assistant","uuid":"a-1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"Read","input":{}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"big file"}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"x"},{"type":"text","text":"and also this"}]}}""",
            """{"type":"assistant","uuid":"a-2","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}""",
            """{"type":"custom-title","customTitle":"Login fix","sessionId":"s1"}""",
        )
        val read = store.read(cwd, "s1")
        assertEquals(listOf(ChatItem.UserText("h-0", "fix it"), ChatItem.UserText("h-1", "and also this"), ChatItem.AssistantText("h-2", "done", false)), read.items)
        assertEquals("a-2", read.lastAssistantUuid)
        assertEquals("Login fix", read.summary?.customTitle)
    }

    @Test fun readGivesTheSummaryTheResumeListShows() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1000, user("first message"), assistant("ok"), """{"type":"custom-title","customTitle":"[archived] Old","sessionId":"s1"}""")
        val fromRead = store.read(cwd, "s1").summary
        assertEquals(store.list(cwd, archived = true).single(), fromRead)
        assertEquals(SessionSummary("s1", "first message", 1000, cwd, "Old", archived = true), fromRead)
        assertEquals(null, store.read(cwd, "missing").summary)
    }
}
