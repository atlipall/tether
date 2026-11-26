package tether.session

/**
 * Whether a chat can be followed and continued from claude.ai or the Claude app (the CLI's Remote Control). It lasts as
 * long as the chat's process: a restarted or reopened chat starts with it off.
 */
sealed interface RemoteControl {
    data object Off : RemoteControl

    /** Asked to turn on; the CLI hasn't answered yet. */
    data object Starting : RemoteControl

    /** On, at [url] (null when the CLI gave none). [reconnecting] while the connection to claude.ai is down and the CLI is getting it back. */
    data class On(val url: String?, val reconnecting: Boolean = false) : RemoteControl

    /** Asked to turn off what is on at [url]; the CLI hasn't answered yet. */
    data class Stopping(val url: String?) : RemoteControl
}
