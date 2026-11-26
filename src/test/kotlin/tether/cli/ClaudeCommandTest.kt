package tether.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCommandTest {
    private val base = listOf(
        "/bin/claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json",
        "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
        "--prompt-suggestions", "true", "--replay-user-messages",
    )

    @Test fun newSessionWithoutMode() =
        assertEquals(base, ClaudeCommand.build("/bin/claude", SessionLaunch(null, null, "/p"), emptyList()))

    @Test fun resumeWithModeAndExtraArgs() = assertEquals(
        base + listOf("--permission-mode", "acceptEdits", "--resume", "abc", "--model", "sonnet"),
        ClaudeCommand.build("/bin/claude", SessionLaunch("abc", PermissionMode.ACCEPT_EDITS, "/p"), listOf("--model", "sonnet")))

    @Test fun modelAndEffortComeAfterExtraArgsSoTheChatsChoiceWins() = assertEquals(
        base + listOf("--resume", "abc", "--model", "opus", "--model", "sonnet", "--effort", "low"),
        ClaudeCommand.build("/bin/claude", SessionLaunch("abc", null, "/p", model = "sonnet", effort = "low"), listOf("--model", "opus")))

    @Test fun newWorktree() = assertEquals(
        base + listOf("--permission-mode", "plan", "--worktree", "wt-1"),
        ClaudeCommand.build("/bin/claude", SessionLaunch(null, PermissionMode.PLAN, "/p", "wt-1"), emptyList()))

    @Test fun rewindResumesAtAMessageInAFork() = assertEquals(
        base + listOf("--resume", "abc", "--resume-session-at", "u-9", "--fork-session"),
        ClaudeCommand.build("/bin/claude", SessionLaunch("abc", null, "/p", resumeAt = "u-9", fork = true), emptyList()))

    @Test fun cwdIsNotAnArgument() =
        assertEquals(base, ClaudeCommand.build("/bin/claude", SessionLaunch(null, null, "/somewhere/else"), emptyList()))

    @Test fun permissionModeFromCli() {
        assertEquals(PermissionMode.PLAN, PermissionMode.fromCli("plan"))
        assertNull(PermissionMode.fromCli("bypassPermissions"))
        assertNull(PermissionMode.fromCli(null))
    }

    @Test fun configuredPathWins() =
        assertEquals("/x/claude", ClaudeBinary.resolve("/x/claude", "/usr/bin", "/home/u") { true })

    @Test fun configuredPathThatIsNotExecutableGivesNull() =
        assertNull(ClaudeBinary.resolve("/x/claude", "/usr/bin", "/home/u") { it != "/x/claude" })

    @Test fun searchesPathThenFallbacks() {
        assertEquals("/b/claude", ClaudeBinary.resolve("", "/a:/b", "/home/u") { it == "/b/claude" })
        assertEquals("/home/u/.local/bin/claude", ClaudeBinary.resolve("", "/a", "/home/u") { it == "/home/u/.local/bin/claude" })
        assertEquals("/opt/homebrew/bin/claude", ClaudeBinary.resolve("  ", null, "/home/u") { it == "/opt/homebrew/bin/claude" })
        assertNull(ClaudeBinary.resolve("", "/a", "/home/u") { false })
    }
}
