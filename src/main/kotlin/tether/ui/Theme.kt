package tether.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.ColorUtil
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

    /** WCAG's minimum contrast for body text, which the chat's secondary and status text are held to. */
    const val MIN_TEXT_CONTRAST = 4.5

    /**
     * [color], moved towards [strong] in small steps until it reaches [MIN_TEXT_CONTRAST] on [background], so secondary
     * and status text stay readable in themes whose own colours are too faint for small type. [strong] when no step does.
     */
    fun readable(color: Color, background: Color, strong: Color): Color =
        (0..10).asSequence()
            .map { ColorUtil.mix(color, strong, it / 10.0) }
            .firstOrNull { ColorUtil.getContrast(it, background) >= MIN_TEXT_CONTRAST } ?: strong

    /** The background the editor's colour scheme gives [key], or [fallback] when the scheme has none. */
    private fun schemeBackground(key: TextAttributesKey, fallback: Color): Color =
        EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.backgroundColor ?: fallback

    /** The green the IDE uses for success, from the theme when it names one. */
    private val success = JBColor.namedColor("Label.successForeground", JBColor(Color(0x2E7D32), Color(0x5FB865)))

    fun message(): String {
        val font = UIUtil.getLabelFont()
        val background = UIUtil.getPanelBackground()
        val foreground = UIUtil.getLabelForeground()
        return buildJsonObject {
            put("type", "theme")
            put("bg", hex(background))
            put("fg", hex(foreground))
            put("muted", hex(readable(UIUtil.getContextHelpForeground(), background, foreground)))
            put("border", hex(JBColor.border()))
            put("accent", hex(JBUI.CurrentTheme.Link.Foreground.ENABLED))
            put("primaryBg", hex(JBUI.CurrentTheme.Button.defaultButtonColorStart()))
            put("primaryFg", hex(JBUI.CurrentTheme.Button.defaultButtonForeground()))
            put("selectionBg", hex(JBUI.CurrentTheme.List.Selection.background(true)))
            put("selectionFg", hex(JBUI.CurrentTheme.List.Selection.foreground(true)))
            put("danger", hex(readable(UIUtil.getErrorForeground(), background, foreground)))
            put("ok", hex(readable(success, background, foreground)))
            put("warn", hex(readable(JBUI.CurrentTheme.Label.warningForeground(), background, foreground)))
            put("warnBg", hex(JBUI.CurrentTheme.Banner.WARNING_BACKGROUND))
            put("warnBorder", hex(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR))
            put("add", hex(schemeBackground(DiffColors.DIFF_INSERTED, JBColor(Color(0xD7F5DD), Color(0x2F5A3A)))))
            put("del", hex(schemeBackground(DiffColors.DIFF_DELETED, JBColor(Color(0xF9D7D7), Color(0x5A2F2F)))))
            put("codeBg", hex(EditorColorsManager.getInstance().globalScheme.defaultBackground))
            put("font", font.family)
            put("fontSize", fontSize(font.size, TetherSettings.getInstance().state.chatFontPercent))
            put("dark", !JBColor.isBright())
        }.toString()
    }
}
