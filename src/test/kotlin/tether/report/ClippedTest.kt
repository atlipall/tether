package tether.report

import org.junit.Assert.assertEquals
import org.junit.Test

class ClippedTest {
    @Test fun shortTextIsKeptWhole() {
        assertEquals("oops", "oops".clipped(4))
    }

    @Test fun longTextIsCutAndSaysHowMuch() {
        assertEquals("abc… (2 more characters)", "abcde".clipped(3))
    }
}
