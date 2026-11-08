package tether.image

import tether.testutil.TestImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HeicTest {
    private lateinit var savedConverter: (ByteArray) -> ByteArray?

    @Before fun saveConverter() {
        savedConverter = Heic.converter
    }

    @After fun restoreConverter() {
        Heic.converter = savedConverter
    }

    private fun ftyp(brand: String): ByteArray = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".toByteArray() + ByteArray(12)

    @Test fun recognisesHeifBrands() {
        assertTrue(Heic.matches(ftyp("heic")))
        assertTrue(Heic.matches(ftyp("mif1")))
        assertFalse(Heic.matches(ftyp("isom")))
        assertFalse(Heic.matches(ftyp("avif")))
        assertFalse(Heic.matches("ftypheic".toByteArray()))
        assertFalse(Heic.matches(TestImages.solid(2, 2, "jpeg")))
    }

    @Test fun heicIsSentAsTheConvertedJpeg() {
        val jpeg = TestImages.solid(40, 30, "jpeg")
        Heic.converter = { jpeg }
        val image = (Images.prepare(ftyp("heic")) as Prepared.Ok).image
        assertEquals(ImageType.JPEG, image.type)
        assertEquals(40 to 30, image.width to image.height)
    }

    @Test fun largeConvertedPhotoIsDownscaled() {
        Heic.converter = { TestImages.solid(4032, 3024, "jpeg") }
        val image = (Images.prepare(ftyp("heic")) as Prepared.Ok).image
        assertEquals(2000 to 1500, image.width to image.height)
    }

    @Test fun refusedWhenItCantBeConverted() {
        Heic.converter = { null }
        assertEquals(Images.HEIC_UNCONVERTIBLE, (Images.prepare(ftyp("heic")) as Prepared.Rejected).reason)
        Heic.converter = { TestImages.solid(2, 2, "png") }
        assertEquals(Images.HEIC_UNCONVERTIBLE, (Images.prepare(ftyp("heic")) as Prepared.Rejected).reason)
    }

    /** A real HEIC of this size made with `sips`; the test is skipped where `sips` can't write HEIC. */
    private fun realHeic(w: Int, h: Int): ByteArray {
        val dir = Files.createTempDirectory("heic-test")
        try {
            val png = dir.resolve("in.png").also { Files.write(it, TestImages.solid(w, h)) }
            val heic = dir.resolve("in.heic")
            val made = try {
                val p = ProcessBuilder("sips", "-s", "format", "heic", png.toString(), "--out", heic.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.exists(heic)
            } catch (e: IOException) {
                false
            }
            assumeTrue("sips can't write HEIC here", made)
            return Files.readAllBytes(heic)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun systemConverterKeepsASmallPhotosSize() {
        val bytes = realHeic(64, 48)
        assertTrue(Heic.matches(bytes))
        assertEquals(64, Heic.longEdge(bytes))
        val image = (Images.prepare(bytes) as Prepared.Ok).image
        assertEquals(ImageType.JPEG, image.type)
        assertEquals(64 to 48, image.width to image.height)
    }

    @Test fun systemConverterShrinksAPhotoTooLargeToDecode() {
        val bytes = realHeic(8064, 6048)
        assertEquals(8064, Heic.longEdge(bytes))
        val image = (Images.prepare(bytes) as Prepared.Ok).image
        assertEquals(ImageType.JPEG, image.type)
        assertEquals(2000 to 1500, image.width to image.height)
    }
}
