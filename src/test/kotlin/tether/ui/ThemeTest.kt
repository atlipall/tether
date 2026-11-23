package tether.ui

import com.intellij.ui.ColorUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class ThemeTest {
    @Test fun hundredPercentIsTheIdeSize() = assertEquals(13, Theme.fontSize(13, 100))

    @Test fun scalesByThePercentage() = assertEquals(26, Theme.fontSize(13, 200))

    @Test fun roundsToAWholePixel() = assertEquals(14, Theme.fontSize(13, 110))

    @Test fun clampsAPercentageOutsideTheOfferedRange() {
        assertEquals(7, Theme.fontSize(13, 0))
        assertEquals(26, Theme.fontSize(13, 5000))
    }

    @Test fun readableKeepsAColourThatAlreadyContrastsEnough() {
        val grey = Color(0x6C, 0x70, 0x7E)
        assertEquals(grey, Theme.readable(grey, Color.WHITE, Color.BLACK))
    }

    @Test fun readableDarkensAFaintGreyOnALightBackground() {
        val background = Color(0xF7, 0xF8, 0xFA)
        val muted = Theme.readable(Color(0x81, 0x85, 0x94), background, Color(0x1E, 0x1F, 0x22))
        assertTrue(ColorUtil.getContrast(muted, background) >= Theme.MIN_TEXT_CONTRAST)
    }

    @Test fun readableFallsBackToTheStrongColour() =
        assertEquals(Color.WHITE, Theme.readable(Color.WHITE, Color.WHITE, Color.WHITE))
}
