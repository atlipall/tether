package tether.session

/**
 * The bar a chat shows when its checkout is on another branch than the one it worked on: [home] is the chat's branch,
 * [current] the one checked out now. [blocked] says why switching back is not offered right now (null: it is),
 * [switching] is true while git switches, and [error] is why the last attempt did not switch.
 */
data class BranchBar(
    val home: String,
    val current: String,
    val blocked: String? = null,
    val switching: Boolean = false,
    val error: String? = null,
) {
    companion object {
        const val THIS_CHAT_BUSY = "Switching waits until this chat's turn has finished."
        const val OTHER_CHAT_BUSY = "Switching waits until the other chat working in this checkout has finished."

        /**
         * The bar for a chat whose branch is [home] while its checkout is on [current]; null when they agree or either is
         * unknown (a chat that has not worked yet, detached HEAD, no repository). Switching is held back while
         * [busyHere] (this chat is working) or [busyElsewhere] (another chat in the same checkout is): the branch would
         * change under a running turn.
         */
        fun of(home: String?, current: String?, busyHere: Boolean, busyElsewhere: Boolean): BranchBar? {
            if (home == null || current == null || home == current) return null
            val blocked = when {
                busyHere -> THIS_CHAT_BUSY
                busyElsewhere -> OTHER_CHAT_BUSY
                else -> null
            }
            return BranchBar(home, current, blocked)
        }

        /** What the bar says when [changes] kept git from switching. */
        fun uncommitted(changes: List<String>): String {
            val count = if (changes.size == 1) "1 uncommitted change" else "${changes.size} uncommitted changes"
            val shown = changes.take(SHOWN_CHANGES).joinToString(", ") + if (changes.size > SHOWN_CHANGES) ", …" else ""
            return "Commit or stash your changes first: the checkout has $count ($shown), and switching would carry them to the other branch."
        }

        private const val SHOWN_CHANGES = 3
    }
}
