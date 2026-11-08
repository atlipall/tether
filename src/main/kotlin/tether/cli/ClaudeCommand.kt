package tether.cli

import java.io.File

enum class PermissionMode(val cli: String, val label: String) {
    DEFAULT("default", "Ask before edits"),
    ACCEPT_EDITS("acceptEdits", "Accept edits"),
    PLAN("plan", "Plan only"),
    AUTO("auto", "Auto");

    companion object {
        fun fromCli(value: String?): PermissionMode? = entries.firstOrNull { it.cli == value }
    }
}

/**
 * What a new CLI process should start as. [cwd] is the process directory; [worktree] asks claude to
 * create `<cwd>/.claude/worktrees/<name>` on branch `worktree-<name>` and run there. [resumeAt] resumes [resumeId]
 * only up to and including that assistant message; [fork] continues under a new session id, leaving [resumeId]'s
 * transcript as it was. [model] and [effort] are the chat's own choices, null to leave them to the CLI's settings.
 */
data class SessionLaunch(
    val resumeId: String?,
    val permissionMode: PermissionMode?,
    val cwd: String,
    val worktree: String? = null,
    val resumeAt: String? = null,
    val fork: Boolean = false,
    val model: String? = null,
    val effort: String? = null,
)

object ClaudeCommand {
    /**
     * The full command line for [launch]. [extraArgs] come before the chat's own `--model` and `--effort`: the last
     * occurrence of each wins, and the chat's choice is the newer one.
     */
    fun build(binary: String, launch: SessionLaunch, extraArgs: List<String>): List<String> = buildList {
        add(binary)
        addAll(listOf(
            "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
            "--prompt-suggestions", "true",
        ))
        launch.permissionMode?.let { add("--permission-mode"); add(it.cli) }
        launch.worktree?.let { add("--worktree"); add(it) }
        launch.resumeId?.let { add("--resume"); add(it) }
        launch.resumeAt?.let { add("--resume-session-at"); add(it) }
        if (launch.fork) add("--fork-session")
        addAll(extraArgs)
        launch.model?.let { add("--model"); add(it) }
        launch.effort?.let { add("--effort"); add(it) }
    }
}

object ClaudeBinary {
    /** Configured path if set (null if not executable); otherwise PATH entries, then common install locations. */
    fun resolve(configured: String, pathEnv: String?, home: String, isExecutable: (String) -> Boolean): String? {
        if (configured.isNotBlank()) return configured.trim().takeIf(isExecutable)
        val fromPath = pathEnv.orEmpty().split(':').filter { it.isNotBlank() }.map { "${it.trimEnd('/')}/claude" }
        val fallbacks = listOf("$home/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude")
        return (fromPath + fallbacks).firstOrNull(isExecutable)
    }

    /** The claude to run for the [configured] path, looked up in the shell environment [env]; null when there is none. */
    fun find(configured: String, env: Map<String, String>): String? =
        resolve(configured, env["PATH"], System.getProperty("user.home")) { File(it).canExecute() }
}
