package tether.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class OrientationTest {
    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** A 40×20 JPEG, blue with a red top-left quadrant, so every turn and mirror puts the red somewhere else. */
    private fun redTopLeft(): ByteArray {
        val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 40, 20)
        g.color = Color.RED
        g.fillRect(0, 0, 20, 10)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
    }

    /**
     * TIFF data whose first IFD, at [ifdOffset], holds a `Make` entry and then the orientation [tag] as a value of
     * TIFF [type].
     */
    private fun tiff(tag: Int, order: ByteOrder, ifdOffset: Int = 10, type: Int = 3): ByteArray {
        val buffer = ByteBuffer.allocate(10 + 2 + 24 + 4).order(order)
        buffer.put(if (order == ByteOrder.BIG_ENDIAN) "MM".toByteArray() else "II".toByteArray())
        buffer.putShort(42).putInt(ifdOffset).putShort(0)
        buffer.putShort(2)
        buffer.putShort(0x010F).putShort(2).putInt(4).put("Abc\u0000".toByteArray())
        buffer.putShort(0x0112).putShort(type.toShort()).putInt(1).putShort(tag.toShort()).putShort(0)
        buffer.putInt(0)
        return buffer.array()
    }

    /** [jpeg] with an `Exif` APP1 segment holding [tiff], placed after its JFIF APP0 segment as cameras do. */
    private fun withExif(jpeg: ByteArray, tiff: ByteArray): ByteArray {
        val afterApp0 = 4 + ((jpeg[4].toInt() and 0xFF) shl 8 or (jpeg[5].toInt() and 0xFF))
        val payload = "Exif\u0000\u0000".toByteArray() + tiff
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        return jpeg.copyOfRange(0, afterApp0) + segment + jpeg.copyOfRange(afterApp0, jpeg.size)
    }

    private fun withOrientation(tag: Int, order: ByteOrder = ByteOrder.BIG_ENDIAN) = withExif(redTopLeft(), tiff(tag, order))

    private fun ok(p: Prepared): Image = (p as? Prepared.Ok)?.image ?: error("expected Ok, got ${(p as Prepared.Rejected).reason}")

    private fun decoded(image: Image): BufferedImage = ImageIO.read(ByteArrayInputStream(image.bytes))

    /** The one corner of [img] that is red, sampled 3 px in from each corner. */
    private fun redCorner(img: BufferedImage): Corner {
        fun isRed(x: Int, y: Int) = Color(img.getRGB(x, y)).let { it.red > 200 && it.blue < 60 }
        val (right, bottom) = img.width - 4 to img.height - 4
        val red = mapOf(
            Corner.TOP_LEFT to isRed(3, 3), Corner.TOP_RIGHT to isRed(right, 3),
            Corner.BOTTOM_LEFT to isRed(3, bottom), Corner.BOTTOM_RIGHT to isRed(right, bottom),
        ).filterValues { it }.keys
        return red.singleOrNull() ?: error("expected one red corner, got $red")
    }

    @Test fun readsTheTagInEitherByteOrder() {
        assertEquals(Orientation.TURNED_RIGHT, Orientation.of(withOrientation(6)))
        assertEquals(Orientation.TURNED_LEFT, Orientation.of(withOrientation(8, ByteOrder.LITTLE_ENDIAN)))
        assertEquals(Orientation.UPRIGHT, Orientation.of(redTopLeft()))
    }

    @Test fun unreadableTagsCountAsUpright() {
        val order = ByteOrder.BIG_ENDIAN
        assertEquals(Orientation.UPRIGHT, Orientation.of(withOrientation(42)))
        assertEquals(Orientation.UPRIGHT, Orientation.of(withExif(redTopLeft(), tiff(6, order, type = 4))))
        assertEquals(Orientation.UPRIGHT, Orientation.of(withExif(redTopLeft(), tiff(6, order, ifdOffset = Int.MAX_VALUE))))
        assertEquals(Orientation.UPRIGHT, Orientation.of(withExif(redTopLeft(), tiff(6, order, ifdOffset = Int.MAX_VALUE - 21))))
        assertEquals(Orientation.UPRIGHT, Orientation.of(withExif(redTopLeft(), tiff(6, order, ifdOffset = -1))))
        assertEquals(Orientation.UPRIGHT, Orientation.of(withExif(redTopLeft(), tiff(6, order, ifdOffset = 36))))
        assertEquals(Orientation.UPRIGHT, Orientation.of(withOrientation(6).copyOf(40)))
    }

    @Test fun uprightJpegIsSentAsItIs() {
        val bytes = withOrientation(1)
        assertSame(bytes, ok(Images.prepare(bytes)).bytes)
    }

    @Test fun turnedRightJpegIsTurnedBeforeSending() {
        val bytes = withOrientation(6)
        val image = ok(Images.prepare(bytes))
        assertNotSame(bytes, image.bytes)
        assertEquals(20 to 40, image.width to image.height)
        val pixels = decoded(image)
        assertEquals(20 to 40, pixels.width to pixels.height)
        assertEquals(Corner.TOP_RIGHT, redCorner(pixels))
        assertEquals(Orientation.UPRIGHT, Orientation.of(image.bytes))
    }

    @Test fun eachOrientationPutsTheRedCornerWhereItBelongs() {
        val expected = mapOf(
            1 to Corner.TOP_LEFT, 2 to Corner.TOP_RIGHT, 3 to Corner.BOTTOM_RIGHT, 4 to Corner.BOTTOM_LEFT,
            5 to Corner.TOP_LEFT, 6 to Corner.TOP_RIGHT, 7 to Corner.BOTTOM_RIGHT, 8 to Corner.BOTTOM_LEFT,
        )
        for ((tag, corner) in expected) {
            val pixels = decoded(ok(Images.prepare(withOrientation(tag))))
            val size = if (tag >= 5) 20 to 40 else 40 to 20
            assertEquals("orientation $tag size", size, pixels.width to pixels.height)
            assertEquals("orientation $tag", corner, redCorner(pixels))
        }
    }

    @Test fun largePhotoIsScaledThenTurned() {
        val big = BufferedImage(3000, 1000, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(big, "jpeg", it) }.toByteArray()
        val image = ok(Images.prepare(withExif(jpeg, tiff(8, ByteOrder.BIG_ENDIAN))))
        assertEquals(667 to 2000, image.width to image.height)
    }

    @Test fun previewIsTurnedToo() {
        val preview = Images.preview(withOrientation(8))!!
        assertEquals(20 to 40, preview.width to preview.height)
        assertTrue(redCorner(decoded(preview)) == Corner.BOTTOM_LEFT)
    }
}
