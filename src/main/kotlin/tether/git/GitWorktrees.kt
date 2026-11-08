package tether.git

import java.io.File

/** One entry of `git worktree list --porcelain`. [branch] is the short branch name, null when detached. */
data class Worktree(val path: String, val branch: String?, val isMain: Boolean) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

object WorktreePorcelain {
    /** The first block is the main worktree. Bare entries are dropped (nothing can run there). */
    fun parse(output: String): List<Worktree> =
        output.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { block -> block.lines().filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
            .mapIndexedNotNull { index, lines ->
                val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ")
                    ?: return@mapIndexedNotNull null
                if ("bare" in lines) return@mapIndexedNotNull null
                val branch = lines.firstOrNull { it.startsWith("branch ") }
                    ?.removePrefix("branch ")?.removePrefix("refs/heads/")
                Worktree(path, branch, isMain = index == 0)
            }
}

/**
 * The project's worktrees. [lister] runs `git worktree list --porcelain`, [brancher] `git branch --list` for worktree branches, in a directory and returns its
 * stdout, or null when git is missing or the directory is not in a repository (tests inject a fake).
 */
class GitWorktrees(
    private val projectDir: String,
    private val brancher: (File) -> String? = ::runGitWorktreeBranches,
    private val lister: (File) -> String? = ::runGitWorktreeList,
) {
    /** Every worktree including the main one; null without git. Runs a process: never call on the EDT. */
    fun list(): List<Worktree>? = lister(File(projectDir))?.let(WorktreePorcelain::parse)

    /** Folders claude --worktree created under `<project>/.claude/worktrees`, sorted. */
    fun claudeWorktreeDirs(): List<String> =
        File(projectDir, ".claude/worktrees").listFiles { f -> f.isDirectory }.orEmpty().map { it.path }.sorted()

    /** Folders whose past sessions can be resumed: the project, every git worktree, every dir under `.claude/worktrees`. */
    fun knownFolders(worktrees: List<Worktree>?): List<String> =
        (listOf(projectDir) + worktrees.orEmpty().map { it.path } + claudeWorktreeDirs()).distinctBy(::canonicalPath)

    /**
     * Names a new worktree must not reuse: existing folders, and leftover `worktree-<name>` branches
     * (`git worktree remove` keeps the branch). Runs a process: never call on the EDT.
     */
    fun existingNames(worktrees: List<Worktree>?): Set<String> =
        (worktrees.orEmpty().filterNot { it.isMain }.map { it.name } + claudeWorktreeDirs().map { File(it).name }).toSet() +
            brancher(File(projectDir))?.let(WorktreeBranches::parse).orEmpty()
}

object WorktreeBranches {
    private const val PREFIX = "worktree-"

    /** `<name>` for every `worktree-<name>` line of `git branch --list --format=%(refname:short)` output. */
    fun parse(output: String): Set<String> =
        output.lines().map { it.trim() }.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.filter { it.isNotEmpty() }.toSet()

    /** What to call a checkout on [branch]: `<name>` for a `worktree-<name>` branch, otherwise the branch itself. */
    fun displayName(branch: String): String = branch.removePrefix(PREFIX).ifEmpty { branch }
}

/** Runs `git branch --list` for the worktree branches in [dir]; null if git is missing, fails, or is slow. */
fun runGitWorktreeBranches(dir: File): String? =
    runGit(dir, "branch", "--list", "--format=%(refname:short)", "worktree-*")

/** Runs `git worktree list --porcelain` in [dir]; null if git is missing, fails, or takes longer than 5 seconds. */
fun runGitWorktreeList(dir: File): String? = runGit(dir, "worktree", "list", "--porcelain")
