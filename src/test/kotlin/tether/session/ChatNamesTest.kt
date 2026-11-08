package tether.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatNamesTest {
    @Test fun defaultNameIsLabelNumberedWhenTaken() {
        assertEquals("main", ChatNames.defaultName("main", emptyList()))
        assertEquals("main 2", ChatNames.defaultName("main", listOf("main", "wt-1")))
        assertEquals("main 3", ChatNames.defaultName("main", listOf("main", "main 2")))
        assertEquals("main 2", ChatNames.defaultName("main", listOf("main", "main 3")))
    }

    @Test fun cleanCollapsesWhitespaceAndBlankIsNone() {
        assertEquals("fix the build", ChatNames.clean("  fix the\n  build "))
        assertEquals(null, ChatNames.clean("   "))
        assertEquals(null, ChatNames.clean(null))
        assertEquals("foo", ChatNames.clean(" [archived]  foo"))
        assertEquals(null, ChatNames.clean("[archived]"))
    }

    @Test fun folderLabel() {
        assertEquals("main", ChatNames.folderLabel("/p/", "/p"))
        assertEquals("wt-1", ChatNames.folderLabel("/p/.claude/worktrees/wt-1", "/p"))
    }

    @Test fun folderLabelComparesCanonicalPaths() {
        val dir = java.nio.file.Files.createTempDirectory("proj").toFile()
        val link = java.nio.file.Files.createSymbolicLink(
            java.io.File(dir.parentFile, dir.name + "-link").toPath(), dir.toPath()).toFile()
        try {
            assertEquals("main", ChatNames.folderLabel(link.path, dir.path))
            assertEquals("main", ChatNames.folderLabel(dir.path + "/", dir.path))
            assertEquals("main", ChatNames.folderLabel(dir.path + "/./", link.path + "/"))
        } finally { link.delete(); dir.deleteRecursively() }
    }

    @Test fun projectChatIsNamedAfterTheBranch() {
        assertEquals("feature-x", ChatNames.branchLabel(ChatSpec.inProject("/p")) { "feature-x" })
        assertEquals("wt-4", ChatNames.branchLabel(ChatSpec.inProject("/p")) { "worktree-wt-4" })
        assertEquals("main", ChatNames.branchLabel(ChatSpec.inProject("/p")) { null })
        assertEquals("wt-2", ChatNames.branchLabel(ChatSpec.newWorktree("/p", "wt-2")) { "feature-x" })
        assertEquals("wt-3", ChatNames.branchLabel(ChatSpec.existingWorktree("/p/.claude/worktrees/wt-3")) { "worktree-wt-3" })
    }

    @Test fun specs() {
        assertEquals(ChatSpec("main", "/p"), ChatSpec.inProject("/p"))
        assertEquals(ChatSpec("wt-2", "/p", worktree = "wt-2"), ChatSpec.newWorktree("/p", "wt-2"))
        assertEquals(ChatSpec("co", "/x/co"), ChatSpec.existingWorktree("/x/co"))
        assertEquals(
            ChatSpec("wt-1", "/p/.claude/worktrees/wt-1", resumeId = "s1", title = "Login fix"),
            ChatSpec.resume("/p", SessionSummary("s1", "t", 0, "/p/.claude/worktrees/wt-1", customTitle = "Login fix")))
        assertEquals(ChatSpec("main", "/p", resumeId = "s2"), ChatSpec.resume("/p", SessionSummary("s2", "t", 0, "/p")))
    }
}
