package tether.session

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The messages the user sent in one chat, oldest first, which Up and Down in its message box step through. A resumed
 * chat starts with the messages its transcript shows ([initial]); each chat keeps its own, so tabs don't share them.
 */
class PromptHistory(initial: List<String> = emptyList()) {
    private var entries: List<String> = initial.fold(emptyList(), ::append)

    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()

    fun entries(): List<String> = synchronized(this) { entries }

    /** Appends [text] (see [append]) and tells subscribers when the list changed. */
    fun add(text: String) = update { append(it, text) }

    /**
     * Replaces every entry with [texts], under the same rules as [add], and tells subscribers when the list changed.
     * For a transcript that arrives after the chat opens, such as an ACP agent replaying a resumed session.
     */
    fun replace(texts: List<String>) = update { texts.fold(emptyList(), ::append) }

    private fun update(change: (List<String>) -> List<String>) {
        val updated = synchronized(this) {
            val next = change(entries)
            if (next == entries) return
            entries = next
            next
        }
        listeners.forEach { it(updated) }
    }

    /** [listener] gets the full list after every change, on the caller's thread. Returns the unsubscribe function. */
    fun subscribe(listener: (List<String>) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    companion object {
        const val CAP = 100

        /** Most recent last; a repeat of the last entry and blank text are not added; only the newest [cap] are kept. */
        fun append(entries: List<String>, text: String, cap: Int = CAP): List<String> {
            if (text.isBlank() || entries.lastOrNull() == text) return entries
            return (entries + text).takeLast(cap)
        }
    }
}
