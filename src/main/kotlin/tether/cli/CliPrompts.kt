package tether.cli

/**
 * Recognises user messages that are CLI markup rather than plain text a person typed: a background task's
 * `<task-notification>`, a slash command or `!` shell command and its output, a local command's output, hook and
 * reminder text, the editor context an IDE extension attaches, and the note the CLI adds when a summary held others'
 * artifact content. Shared by the live stream ([CliEvent.UserText.fromCli]) and transcripts read back
 * ([tether.session.SessionStore]), so a chat shows the same messages before and after a restart.
 */
object CliPrompts {
    /** The tags such a message opens with, at the very start once leading whitespace is skipped. */
    private val OPENING_TAG = Regex(
        "^<(task-notification|command-name|command-message|command-args|bash-input|bash-stdout|bash-stderr" +
            "|local-command[a-z-]*|system-reminder|user-prompt-submit-hook|user-memory-input|ide_[a-z_]+" +
            "|artifact-content-authored-by-others)[\\s/>]"
    )

    /** Whether [text] opens with a tag the CLI writes; a message that only starts with some other tag was typed. */
    fun isFromCli(text: String): Boolean = OPENING_TAG.containsMatchIn(text.trimStart())
}
