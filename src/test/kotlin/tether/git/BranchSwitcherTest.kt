package tether.git

import tether.testutil.git
import tether.testutil.gitAvailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BranchSwitcherTest {
    private lateinit var repo: File

    @Before fun setUp() {
        assumeTrue(gitAvailable())
        repo = Files.createTempDirectory("switch").toFile()
        git(repo, "init", "-q", "-b", "main")
        File(repo, "a.txt").writeText("a\n")
        git(repo, "add", "a.txt")
        git(repo, "commit", "-q", "-m", "one")
        git(repo, "branch", "feature")
    }

    @After fun tearDown() {
        if (::repo.isInitialized) repo.deleteRecursively()
    }

    @Test fun switchesACleanCheckout() {
        assertEquals(SwitchOutcome.Switched, BranchSwitcher().switch(repo.path, "feature"))
        assertEquals("feature", currentBranch(repo.path))
    }

    @Test fun refusesWhileTrackedOrUntrackedChangesWouldTravelAlong() {
        File(repo, "a.txt").writeText("changed\n")
        File(repo, "new.txt").writeText("new\n")
        assertEquals(SwitchOutcome.Uncommitted(listOf("a.txt", "new.txt")), BranchSwitcher().switch(repo.path, "feature"))
        assertEquals("main", currentBranch(repo.path))
    }

    @Test fun ignoredFilesDoNotCount() {
        File(repo, ".gitignore").writeText("build/\n")
        git(repo, "add", ".gitignore")
        git(repo, "commit", "-q", "-m", "ignore")
        File(repo, "build").mkdirs()
        File(repo, "build/out.txt").writeText("x\n")
        git(repo, "branch", "-f", "feature")
        assertEquals(SwitchOutcome.Switched, BranchSwitcher().switch(repo.path, "feature"))
    }

    @Test fun aMissingBranchFailsWithGitsReason() {
        val outcome = BranchSwitcher().switch(repo.path, "gone")
        assertTrue(outcome.toString(), outcome is SwitchOutcome.Failed && "gone" in outcome.message)
        assertEquals("main", currentBranch(repo.path))
    }

    @Test fun findsTheCheckoutRootFromASubfolder() {
        val sub = File(repo, "src/main").apply { mkdirs() }
        assertEquals(repo.path, checkoutRoot(sub.path))
        assertEquals(null, checkoutRoot(Files.createTempDirectory("plain").toFile().path))
    }
}
