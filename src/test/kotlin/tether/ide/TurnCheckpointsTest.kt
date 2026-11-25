package tether.ide

import tether.session.ChatItem
import tether.session.ChatSpec
import tether.session.RevertChoice
import tether.session.Chats
import tether.session.SessionStore
import tether.testutil.FakeConnection
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import tether.testutil.Fx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.nio.file.Files

/** Reverting a chat's checkout to a message's Local History label, on real files. */
class TurnCheckpointsTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private val chats get() = Chats.getInstance(project)
    private lateinit var conn: FakeConnection
    private val root get() = myFixture.tempDirFixture.getFile("")!!

    override fun setUp() {
        super.setUp()
        PsiTestUtil.addContentRoot(module, root)
        chats.closeAll()
        chats.connector = { FakeConnection().also { conn = it } }
        chats.probeConnector = { FakeConnection() }
        chats.store = SessionStore(Files.createTempDirectory("claude-home").toFile())
    }

    override fun tearDown() {
        try {
            chats.closeAll()
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun write(name: String, text: String, dir: com.intellij.openapi.vfs.VirtualFile = root) = WriteAction.run<Exception> {
        val file = dir.findChild(name) ?: dir.createChildData(this, name)
        VfsUtil.saveText(file, text)
    }

    /** Sends [text] and ends its turn, once its checkpoint is in (it is put in the background). */
    private fun turn(chat: tether.session.Chat, text: String): ChatItem.UserText {
        chat.send(text)
        return endTurn(chat)
    }

    /** Ends the turn of the message just sent, once its checkpoint is in (it is put in the background). */
    private fun endTurn(chat: tether.session.Chat): ChatItem.UserText {
        val item = sent(chat).last()
        PlatformTestUtil.waitWithEventsDispatching("The message was not marked",
            { TurnCheckpoints.getInstance(project).hasCheckpoint(chat.id, item.id) }, 10)
        conn.emit(Fx.result())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return sent(chat).last()
    }

    /** The chat's messages as the page would get them. */
    private fun sent(chat: tether.session.Chat): List<ChatItem.UserText> {
        var shown = emptyList<ChatItem>()
        chat.attach(object : tether.session.SessionView {
            override fun reset(items: List<ChatItem>) { shown = items }
            override fun item(item: ChatItem) {}
            override fun state(state: tether.session.SessionState, mode: String?) {}
            override fun insert(text: String) { inserted += text }
        })
        return shown.filterIsInstance<ChatItem.UserText>()
    }
    private val inserted = mutableListOf<String>()

    private var asked: String? = null

    /** Answers the revert dialog with button [index]: the offered [RevertChoice]s in their order, then Cancel. */
    private fun answer(index: Int) = TestDialogManager.setTestDialog { message -> asked = message; index }

    /** The dialog's buttons on a tracked checkout, where every [RevertChoice] is offered. */
    private val filesAndConversation = RevertChoice.FILES_AND_CONVERSATION.ordinal
    private val filesOnly = RevertChoice.FILES.ordinal
    private val conversationOnly = RevertChoice.CONVERSATION.ordinal
    private val cancel = RevertChoice.entries.size

    private fun read(name: String) = root.findChild(name)?.let { VfsUtil.loadText(it) }

    /** A folder that is no content root, so Local History does not track it. */
    private fun untrackedFolder() = VfsUtil.findFile(Files.createTempDirectory("untracked"), true)!!

    /** Gives a label put straight from the pooled thread time to land inside the open command's change set. */
    private fun letTheLabelLandInTheOpenCommand() = Thread.sleep(500)

    fun testRevertRestoresTheFilesAsTheyWereBeforeTheMessage() {
        write("a.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        val item = turn(chat, "change it")
        write("a.txt", "after")
        write("new.txt", "created by Claude")
        answer(filesOnly)
        chat.revertTo(item.id)
        assertEquals("before", read("a.txt"))
        assertNull("files created since are removed", read("new.txt"))
        assertTrue(TurnCheckpoints.getInstance(project).consumeNote(chat.id)!!.contains("reverted"))
    }

    /**
     * A label put while a change set is open (a command or VFS refresh, in any open project) joins it, and Local
     * History's revert skips change sets that change nothing in the reverted folder, label and all.
     */
    fun testAMessageSentWhileAnotherFolderChangesCanStillBeRevertedTo() {
        write("d.txt", "before")
        val elsewhere = VfsUtil.findFile(Files.createTempDirectory("elsewhere"), true)!!
        PsiTestUtil.addContentRoot(module, elsewhere)
        val chat = chats.open(ChatSpec.inProject(root.path))
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, {
            write("x.txt", "typed meanwhile", elsewhere)
            chat.send("change it")
            letTheLabelLandInTheOpenCommand()
        }, "Typing", null)
        val item = endTurn(chat)
        write("d.txt", "after")
        answer(filesOnly)
        chat.revertTo(item.id)
        assertEquals("before", read("d.txt"))
    }

    fun testARunningTurnIsNotReverted() {
        write("c.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        chat.send("change it")
        val id = sent(chat).single().id
        PlatformTestUtil.waitWithEventsDispatching("The message was not marked",
            { TurnCheckpoints.getInstance(project).hasCheckpoint(chat.id, id) }, 10)
        write("c.txt", "after")
        answer(filesOnly)
        chat.revertTo(id)
        assertEquals("after", read("c.txt"))
    }

    fun testCancellingTheQuestionChangesNothing() {
        write("b.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        val item = turn(chat, "change it")
        write("b.txt", "after")
        answer(cancel)
        chat.revertTo(item.id)
        assertEquals("after", read("b.txt"))
        assertNull(TurnCheckpoints.getInstance(project).consumeNote(chat.id))
    }

    fun testRevertingFilesAndConversationDropsTheMessageAndRefillsTheInputBox() {
        write("f.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        chat.start()
        conn.emit(Fx.init("sess-1", cwd = root.path))
        chat.send("first")
        conn.emit(Fx.assistantText("m1", "ok", uuid = "a-1"))
        endTurn(chat)
        val second = turn(chat, "second")
        write("f.txt", "after")
        val old = conn
        answer(filesAndConversation)
        chat.revertTo(second.id)
        assertEquals("before", read("f.txt"))
        assertTrue(old.stopped)
        assertEquals(listOf("first"), sent(chat).map { it.text })
        assertEquals(listOf("second"), inserted)
        assertNull("files and memory agree again", TurnCheckpoints.getInstance(project).consumeNote(chat.id))
    }

    fun testRevertingOnlyTheConversationLeavesTheFiles() {
        write("g.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        chat.start()
        conn.emit(Fx.init("sess-1", cwd = root.path))
        val item = turn(chat, "change it")
        write("g.txt", "after")
        answer(conversationOnly)
        chat.revertTo(item.id)
        assertEquals("after", read("g.txt"))
        assertTrue(sent(chat).isEmpty())
        assertTrue(TurnCheckpoints.getInstance(project).consumeNote(chat.id)!!.contains("may still"))
    }

    fun testAnUntrackedCheckoutOffersOnlyTheConversation() {
        val outside = untrackedFolder()
        val chat = chats.open(ChatSpec.inProject(outside.path))
        chat.start()
        conn.emit(Fx.init("sess-1", cwd = outside.path))
        chat.send("change it")
        conn.emit(Fx.result())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val item = sent(chat).single()
        assertTrue("the link shows without a checkpoint", item.revertable)
        val conversationOnlyAsTheOnlyChoice = 0
        answer(conversationOnlyAsTheOnlyChoice)
        chat.revertTo(item.id)
        assertTrue(asked!!.contains("can't be reverted"))
        assertTrue(sent(chat).isEmpty())
    }

    fun testATurnOpeningWhileTheQuestionIsUpChangesNothing() {
        write("h.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        chat.start()
        conn.emit(Fx.init("sess-1", cwd = root.path))
        val item = turn(chat, "change it")
        write("h.txt", "after")
        TestDialogManager.setTestDialog { _ -> chat.send("meanwhile"); filesAndConversation }
        chat.revertTo(item.id)
        assertEquals("after", read("h.txt"))
        assertEquals(listOf("change it", "meanwhile"), sent(chat).map { it.text })
        assertTrue(inserted.isEmpty())
        assertNull(TurnCheckpoints.getInstance(project).consumeNote(chat.id))
    }
}
