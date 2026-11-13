package tether.git

/** Names for `claude --worktree <name>` (folder `.claude/worktrees/<name>`, branch `worktree-<name>`). */
object WorktreeNames {
    private val VALID = Regex("[A-Za-z0-9._-]+")
    private val SEPARATORS = Regex("""[\s/\\]""")

    /**
     * Why [name] cannot be used, or null when it can. A leading '-' would be read by claude as an option; `..`, a
     * trailing '.' and `.lock` are refused by git in the branch name `worktree-<name>` (and '.' or '..' are not folders
     * we can create); "main" (in any case) names the project's own checkout.
     */
    fun error(name: String, existing: Set<String>): String? = when {
        name.isEmpty() -> "Enter a name."
        !VALID.matches(name) -> "Use only letters, digits, '.', '_' and '-'."
        name.startsWith("-") -> "The name cannot start with '-'."
        name.contains("..") || name.endsWith(".") || name.endsWith(".lock") -> "Git cannot use this name for a branch."
        name.equals("main", ignoreCase = true) -> "\"main\" is reserved for the project itself."
        name in existing -> "A worktree named '$name' already exists."
        else -> null
    }

    /**
     * [typed] as it goes into the name field: whitespace and path separators ('/', '\') become '-', so "my feature"
     * and "feature/login" name the worktrees `my-feature` and `feature-login`.
     */
    fun fromTyped(typed: String): String = typed.replace(SEPARATORS, "-")

    fun suggest(existing: Set<String>): String =
        generateSequence(1) { it + 1 }.map { "wt-$it" }.first { it !in existing }
}
