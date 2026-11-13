package tether.ide

import tether.git.currentBranch
import tether.session.BranchBar
import tether.session.Chat
import tether.session.ChatSpec
import tether.session.Chats
import tether.session.SessionStore
import tether.testutil.FakeConnection
import tether.testutil.Fx
import tether.testutil.git
import tether.testutil.gitAvailable
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class BranchGuardTest : BasePlatformTestCase() {
    private val connections = mutableListOf<FakeConnection>()
    private val chats get() = Chats.getInstance(project)
    private val guard get() = BranchGuard.getInstance(project)
    private lateinit var home: File
    private var repo: File? = null
    private val reloaded = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        chats.closeAll()
        home = Files.createTempDirectory("claude-home").toFile()
        chats.store = SessionStore(home)
        chats.connector = { FakeConnection().also { connections += it } }
        guard.background = Runnable::run
        guard.onEdt = Runnable::run
        guard.reload = { reloaded += it }
    }

    override fun tearDown() {
        try {
            chats.closeAll()
            home.deleteRecursively()
            repo?.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** A repository on `main` with a `feature` branch; null (test skipped) without git. */
    private fun gitRepo(): File? {
        if (!gitAvailable()) return null
        val dir = Files.createTempDirectory("guard-repo").toFile().canonicalFile
        git(dir, "init", "-q", "-b", "main")
        git(dir, "commit", "-q", "--allow-empty", "-m", "one")
        git(dir, "branch", "feature")
        repo = dir
        return dir
    }

    /** Opens a chat in [dir] that runs one whole turn on [branch], leaving the checkout there. */
    private fun chatThatWorkedOn(dir: File, branch: String): Pair<Chat, FakeConnection> {
        git(dir, "checkout", "-q", branch)
        val chat = chats.open(ChatSpec.inProject(dir.path))
        chat.send("work")
        val connection = connections.last()
        PlatformTestUtil.waitWithEventsDispatching("The message was not sent", { connection.userMessages().isNotEmpty() }, 10)
        connection.emit(Fx.result())
        return chat to connection
    }

    fun testAChatWarnsWhenItsCheckoutMovedToAnotherBranchAndSwitchesBack() {
        val dir = gitRepo() ?: return
        val (chat, _) = chatThatWorkedOn(dir, "feature")
        assertEquals("feature", chat.homeBranch)
        guard.refresh()
        assertNull("no bar while the checkout is on the chat's branch", chat.branchBar)

        git(dir, "checkout", "-q", "main")
        guard.refresh()
        assertEquals(BranchBar("feature", "main"), chat.branchBar)

        guard.switch(chat)
        assertEquals("feature", currentBranch(dir.path))
        assertNull(chat.branchBar)
        assertEquals(listOf(dir.path), reloaded)
    }

    fun testUncommittedChangesKeepTheCheckoutWhereItIs() {
        val dir = gitRepo() ?: return
        val (chat, _) = chatThatWorkedOn(dir, "feature")
        git(dir, "checkout", "-q", "main")
        File(dir, "notes.txt").writeText("work in progress\n")
        guard.switch(chat)
        assertEquals("main", currentBranch(dir.path))
        assertEquals(BranchBar.uncommitted(listOf("notes.txt")), chat.branchBar?.error)
        assertEquals(emptyList<String>(), reloaded)
    }

    fun testADismissedBarStaysHiddenUntilTheBranchesChange() {
        val dir = gitRepo() ?: return
        git(dir, "branch", "other")
        val (chat, _) = chatThatWorkedOn(dir, "feature")
        git(dir, "checkout", "-q", "main")
        guard.refresh()
        guard.dismiss(chat)
        assertNull(chat.branchBar)
        guard.refresh()
        assertNull("still dismissed for the same branches", chat.branchBar)
        git(dir, "checkout", "-q", "other")
        guard.refresh()
        assertEquals(BranchBar("feature", "other"), chat.branchBar)
    }

    fun testSwitchingWaitsWhileAnotherChatWorksInTheSameCheckout() {
        val dir = gitRepo() ?: return
        val (chat, _) = chatThatWorkedOn(dir, "feature")
        val (other, otherConnection) = chatThatWorkedOn(dir, "main")
        other.send("more work")
        PlatformTestUtil.waitWithEventsDispatching("The message was not sent", { otherConnection.userMessages().size == 2 }, 10)
        guard.refresh()
        assertEquals(BranchBar.OTHER_CHAT_BUSY, chat.branchBar?.blocked)
        guard.switch(chat)
        assertEquals("main", currentBranch(dir.path))

        otherConnection.emit(Fx.result())
        guard.refresh()
        assertNull(chat.branchBar?.blocked)
        assertEquals(BranchBar("feature", "main"), chat.branchBar)
    }

    fun testABranchTheChatsOwnTurnMovesToRaisesNoWarningAndIsShownAsCurrent() {
        val dir = gitRepo() ?: return
        val (chat, connection) = chatThatWorkedOn(dir, "main")
        val (other, _) = chatThatWorkedOn(dir, "main")
        chat.send("start a branch")
        PlatformTestUtil.waitWithEventsDispatching("The message was not sent", { connection.userMessages().size == 2 }, 10)
        git(dir, "checkout", "-q", "-b", "login-fix")
        guard.refresh()
        assertNull("the chat's own move, mid-turn, is no reason to warn", chat.branchBar)
        assertEquals("login-fix", chat.homeBranch)
        assertEquals("login-fix", chat.shownBranch)
        assertEquals("another chat in the checkout is warned, and waits for the turn", BranchBar("main", "login-fix", BranchBar.OTHER_CHAT_BUSY), other.branchBar)
        assertEquals("login-fix", other.shownBranch)
        connection.emit(Fx.result())
        guard.refresh()
        assertNull(chat.branchBar)
        assertEquals(BranchBar("main", "login-fix"), other.branchBar)
    }

    fun testTheCurrentBranchIsShownAndHiddenWhenDetached() {
        val dir = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(dir.path))
        guard.refresh()
        assertEquals("main", chat.shownBranch)
        git(dir, "checkout", "-q", "--detach")
        guard.refresh()
        assertNull(chat.shownBranch)
    }

    fun testANewChatThatHasNotWorkedHasNoBranchToGoBackTo() {
        val dir = gitRepo() ?: return
        val chat = chats.open(ChatSpec.inProject(dir.path))
        git(dir, "checkout", "-q", "feature")
        guard.refresh()
        assertNull(chat.homeBranch)
        assertNull(chat.branchBar)
    }
}
