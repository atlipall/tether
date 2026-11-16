package tether.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TetherSettingsTest {
    @Test fun followingIsOnByDefault() = assertTrue(TetherSettings.State().followCheckout)

    @Test fun editorContextIsOnByDefault() = assertTrue(TetherSettings.State().attachEditorContext)

    @Test fun chatFontMatchesTheIdeByDefault() = assertEquals(100, TetherSettings.State().chatFontPercent)
}
