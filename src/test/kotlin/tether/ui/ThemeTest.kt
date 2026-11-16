package tether.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test fun hundredPercentIsTheIdeSize() = assertEquals(13, Theme.fontSize(13, 100))

    @Test fun scalesByThePercentage() = assertEquals(26, Theme.fontSize(13, 200))

    @Test fun roundsToAWholePixel() = assertEquals(14, Theme.fontSize(13, 110))

    @Test fun clampsAPercentageOutsideTheOfferedRange() {
        assertEquals(7, Theme.fontSize(13, 0))
        assertEquals(26, Theme.fontSize(13, 5000))
    }
}
