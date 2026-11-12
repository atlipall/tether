package tether.git

import java.io.File

/** What git printed (stdout and stderr together) and how it exited; exit code -1 when it could not run or timed out. */
data class GitOutput(val exitCode: Int, val text: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/** How [BranchSwitcher.switch] ended. */
sealed interface SwitchOutcome {
    data object Switched : SwitchOutcome

    /** Nothing was switched: the checkout has uncommitted [changes] (`git status --porcelain` paths), which would travel along. */
    data class Uncommitted(val changes: List<String>) : SwitchOutcome

    /** Git refused or failed; [message] is what it said. */
    data class Failed(val message: String) : SwitchOutcome
}

/**
 * Switches a checkout back to a chat's branch, but only when nothing is uncommitted: changes left in the working tree
 * belong to the branch being left, and `git switch` would silently carry them over. Untracked files count too, since
 * a chat's new files are uncommitted work; ignored ones do not. Blocks: call off the EDT.
 */
class BranchSwitcher(private val git: (dir: File, args: List<String>) -> GitOutput = ::runGitWithOutput) {
    fun switch(dir: String, branch: String): SwitchOutcome {
        val folder = File(dir)
        val status = git(folder, listOf("status", "--porcelain"))
        if (!status.succeeded) return SwitchOutcome.Failed(status.text.trim().ifEmpty { "git status failed." })
        val changes = status.text.lines().filter { it.isNotBlank() }.map { it.drop(STATUS_PREFIX).trim() }
        if (changes.isNotEmpty()) return SwitchOutcome.Uncommitted(changes)
        val switched = git(folder, listOf("switch", branch))
        return if (switched.succeeded) SwitchOutcome.Switched
        else SwitchOutcome.Failed(switched.text.trim().ifEmpty { "git switch $branch failed." })
    }

    private companion object {
        /** `git status --porcelain` puts two status letters and a space before each path. */
        const val STATUS_PREFIX = 3
    }
}
