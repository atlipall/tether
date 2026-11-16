package tether.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tether.settings.TetherSettings
import java.awt.Color
import kotlin.math.roundToInt

/** IDE colours and font for the web view, the font sized by the chat font percentage in [TetherSettings]. */
object Theme {
    /** Told when something the theme message carries changes outside the IDE's own theme events, such as Tether's font size setting. */
    fun interface Listener {
        /** Called on the thread that published, the EDT for the settings page; the listener re-reads [message]. */
        fun themeChanged()
    }

    /** Application-wide: the settings page publishes on [changed] and each open chat panel subscribes. */
    @Topic.AppLevel
    val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

    /** Tells every open chat to re-read the theme. */
    fun changed() = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).themeChanged()

    private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    /**
     * The chat's font size in pixels: [percent] of the IDE's UI font size [ideSize], rounded to a whole pixel. A
     * [percent] outside [TetherSettings.CHAT_FONT_PERCENTS], as a hand-edited settings file could hold, is clamped to it.
     */
    fun fontSize(ideSize: Int, percent: Int): Int =
        (ideSize * percent.coerceIn(TetherSettings.CHAT_FONT_PERCENTS) / 100.0).roundToInt()

    fun message(): String {
        val font = UIUtil.getLabelFont()
        return buildJsonObject {
            put("type", "theme")
            put("bg", hex(UIUtil.getPanelBackground()))
            put("fg", hex(UIUtil.getLabelForeground()))
            put("muted", hex(UIUtil.getContextHelpForeground()))
            put("border", hex(JBColor.border()))
            put("accent", hex(JBUI.CurrentTheme.Link.Foreground.ENABLED))
            put("danger", hex(UIUtil.getErrorForeground()))
            put("codeBg", hex(EditorColorsManager.getInstance().globalScheme.defaultBackground))
            put("font", font.family)
            put("fontSize", fontSize(font.size, TetherSettings.getInstance().state.chatFontPercent))
            put("dark", !JBColor.isBright())
        }.toString()
    }
}
