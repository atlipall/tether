package tether.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** The plugin's settings. The state name and file are from before the rename to Tether; changing them would drop saved settings. */
@Service(Service.Level.APP)
@State(name = "ClaudeChatSettings", storages = [Storage("claudeChat.xml")])
class TetherSettings : SimplePersistentStateComponent<TetherSettings.State>(State()) {
    class State : BaseState() {
        /** Empty = auto-detect. */
        var claudePath by string("")
        /** Empty = whatever the CLI's own settings say. */
        var defaultMode by string("")
        var extraArgs by string("")
        /** The IDE window follows the selected chat's checkout (Project view scope, open editors, Find scope). */
        var followCheckout by property(true)
        /** Messages tell the agent which file, line, selection and function the editor shows (location only). */
        var attachEditorContext by property(true)
        /**
         * Claude can call the IDE's tools: diagnostics and find usages, plus quick fixes, rename and move, which ask for
         * permission like Claude's own edits (applies to chats started or restarted after).
         */
        var ideTools by property(true)
        /** The chat's font size as a percentage of the IDE's UI font size; 100 follows the IDE. */
        var chatFontPercent by property(DEFAULT_CHAT_FONT_PERCENT)
        /** After each of Claude's edits, the IDE checks the file and tells Claude about errors in it. */
        var checkEdits by property(true)
        /** While the IDE is in the background, its Dock or taskbar icon asks for attention when a chat waits or finishes. */
        var requestAppAttention by property(true)
        /** Command line of the ACP agent new chats offer besides Claude; empty offers none. */
        var acpCommand by string("hermes acp")
        /** The agent last selected in a new chat (`claude` or `acp`); the next new chat starts with it. */
        var lastAgent by string("")
        /** Whether [savedFindScope] holds the user's own Find in Files default while a plugin scope replaces it. */
        var findScopeSaved by property(false)
        /** The user's own Find in Files default scope (null: none) to put back; see `SearchDefaults`. */
        var savedFindScope by string(null)
    }

    companion object {
        /** The chat font percentage that matches the IDE's UI font size. */
        const val DEFAULT_CHAT_FONT_PERCENT = 100
        /** The percentages the settings page offers for the chat font. */
        val CHAT_FONT_PERCENTS = 50..200
        /** How far one step of the settings page's percentage spinner moves. */
        const val CHAT_FONT_PERCENT_STEP = 10

        fun getInstance(): TetherSettings = service()
    }
}
