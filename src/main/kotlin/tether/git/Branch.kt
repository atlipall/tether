package tether.git

import java.io.File

private const val BRANCH_REF = "ref: refs/heads/"

/**
 * The branch checked out in [dir]'s repository, read from its HEAD file (no git process, so safe on the EDT); null
 * when detached, outside a repository, or unreadable.
 */
fun currentBranch(dir: String): String? = try {
    dotGit(dir)?.let(::gitDir)
        ?.let { File(it, "HEAD").readText().trim() }
        ?.takeIf { it.startsWith(BRANCH_REF) }
        ?.removePrefix(BRANCH_REF)
        ?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

/** Whether [dir] lies in a git checkout: a `.git` in it or a folder above. No git process, so safe on the EDT. */
fun insideRepository(dir: String): Boolean = try {
    dotGit(dir) != null
} catch (_: Exception) {
    false
}

/**
 * The folder of [dir]'s checkout: the nearest folder at or above it that holds a `.git`. Chats in the same checkout
 * share one working tree and one HEAD. Null outside a repository. No git process, so safe on the EDT.
 */
fun checkoutRoot(dir: String): String? = try {
    dotGit(dir)?.parentFile?.path
} catch (_: Exception) {
    null
}

private fun dotGit(dir: String): File? =
    generateSequence(File(dir).absoluteFile) { it.parentFile }.map { File(it, ".git") }.firstOrNull { it.exists() }

/** A linked worktree's `.git` is a file, `gitdir: <path>` (absolute or relative to the worktree). */
private fun gitDir(dotGit: File): File {
    if (!dotGit.isFile) return dotGit
    val target = File(dotGit.readText().trim().removePrefix("gitdir:").trim())
    return if (target.isAbsolute) target else File(dotGit.parentFile, target.path)
}
