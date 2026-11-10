package tether.context

import tether.follow.CheckoutMapper
import tether.follow.Placement
import tether.git.canonicalPath

object ContextPaths {
    /**
     * [file] as the chat working in [cwd] names it, comparing canonical paths. When the chat's folder is a checkout of
     * the project at [projectDir] and [file] lies in another checkout, the answer is the file's counterpart in the
     * chat's checkout, if [exists] says it is there. Otherwise it is [file] relative to the chat's folder. Null when
     * neither applies, including a worktree file with no counterpart seen from main (main does not own
     * `.claude/worktrees`, as in v1's edit notes).
     *
     * A new worktree chat works in a predicted folder that git creates on the first message, from HEAD: until then a
     * main file's path relative to main is the path it will have there.
     */
    fun forChat(file: String, cwd: String, projectDir: String, exists: (String) -> Boolean): String? {
        val mapper = CheckoutMapper(projectDir)
        val path = canonicalPath(file)
        val chat = (mapper.place(cwd) as? Placement.Followable)?.checkout
        val home = chat?.let { mapper.checkoutOf(path) }
        if (chat != null && home != null && home != chat) {
            if (home.isMain && !exists(chat.dir)) return relativeTo(path, home.dir)
            return mapper.counterpart(path, home, chat)?.takeIf(exists)?.let { relativeTo(it, chat.dir) }
        }
        return relativeTo(path, chat?.dir ?: canonicalPath(cwd))
    }

    /**
     * [file] as the editor-context note names it: as [forChat] does, else by its absolute path when it lies outside
     * every checkout of the project at [projectDir] (a scratch file, a log, another repository), since Claude can read
     * it there. Null for a file in the project that the chat has no name for (one in another checkout with no
     * counterpart in the chat's, or any project file seen from a chat outside the project), so Claude is never
     * pointed at a checkout it doesn't work in.
     */
    fun forNote(file: String, cwd: String, projectDir: String, exists: (String) -> Boolean): String? =
        forChat(file, cwd, projectDir, exists)
            ?: canonicalPath(file).takeIf { CheckoutMapper(projectDir).checkoutOf(it) == null }

    private fun relativeTo(path: String, dir: String): String? =
        path.removePrefix(dir.trimEnd('/') + "/").takeIf { it != path && it.isNotEmpty() }
}
