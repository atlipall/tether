package tether.image

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

/**
 * A JPEG's EXIF orientation: how its stored pixels must be turned or mirrored to show the right way up. Cameras and
 * `sips` (converting an iPhone HEIC) store the pixels as the sensor saw them and set this tag instead of rotating.
 * ImageIO ignores the tag and Claude may too, so an image whose tag isn't [UPRIGHT] is turned with [apply] and
 * re-encoded without it. Pure: no IDE API.
 */
internal enum class Orientation(val tag: Int, private val swapsEdges: Boolean) {
    UPRIGHT(1, false),
    MIRRORED(2, false),
    UPSIDE_DOWN(3, false),
    UPSIDE_DOWN_MIRRORED(4, false),
    TRANSPOSED(5, true),
    TURNED_RIGHT(6, true),
    TRANSVERSED(7, true),
    TURNED_LEFT(8, true);

    /** The transform taking a stored [w]×[h] image's pixels to where they belong in the upright one. */
    private fun transform(w: Int, h: Int): AffineTransform = when (this) {
        UPRIGHT -> AffineTransform()
        MIRRORED -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w.toDouble(), 0.0)
        UPSIDE_DOWN -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w.toDouble(), h.toDouble())
        UPSIDE_DOWN_MIRRORED -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h.toDouble())
        TRANSPOSED -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        TURNED_RIGHT -> AffineTransform(0.0, 1.0, -1.0, 0.0, h.toDouble(), 0.0)
        TRANSVERSED -> AffineTransform(0.0, -1.0, -1.0, 0.0, h.toDouble(), w.toDouble())
        TURNED_LEFT -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w.toDouble())
    }

    /** [img] the right way up: itself when [UPRIGHT], otherwise a turned or mirrored copy. */
    fun apply(img: BufferedImage): BufferedImage {
        if (this == UPRIGHT) return img
        val (w, h) = if (swapsEdges) img.height to img.width else img.width to img.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(img, transform(img.width, img.height), null)
        } finally {
            g.dispose()
        }
        return out
    }

    companion object {
        private const val TAG_ORIENTATION = 0x0112
        private const val TIFF_TYPE_SHORT = 3
        private const val MARKER_APP1 = 0xE1
        private const val MARKER_START_OF_SCAN = 0xDA
        private const val MARKER_END_OF_IMAGE = 0xD9

        /** [bytes]' orientation when they are a JPEG with an EXIF orientation tag; [UPRIGHT] otherwise or when it can't be read. */
        fun of(bytes: ByteArray): Orientation {
            if (ImageType.sniff(bytes) != ImageType.JPEG) return UPRIGHT
            val tag = tiffData(bytes)?.let { orientationTag(bytes, it) } ?: return UPRIGHT
            return entries.firstOrNull { it.tag == tag } ?: UPRIGHT
        }

        /**
         * Where the TIFF data of the JPEG's `Exif` APP1 segment lies (to the segment's end), found by walking the
         * segments before the scan, fill bytes skipped; null when there is none.
         */
        private fun tiffData(b: ByteArray): IntRange? {
            var i = 2
            while (true) {
                while (i + 1 < b.size && b.u8(i) == 0xFF && b.u8(i + 1) == 0xFF) i++
                if (i + 4 > b.size || b.u8(i) != 0xFF) return null
                val marker = b.u8(i + 1)
                if (marker == MARKER_START_OF_SCAN || marker == MARKER_END_OF_IMAGE) return null
                val length = b.u16(i + 2, bigEndian = true)
                if (length < 2) return null
                val end = i + 2 + length
                if (marker == MARKER_APP1 && b.has(i + 4, 'E', 'x', 'i', 'f', 0, 0)) return (i + 10) until minOf(end, b.size)
                i = end
            }
        }

        /**
         * The orientation value in the first IFD of the TIFF data at [tiff], or null when it is absent, not a SHORT,
         * or any offset points outside [tiff].
         */
        private fun orientationTag(b: ByteArray, tiff: IntRange): Int? {
            val start = tiff.first
            val end = tiff.last + 1
            fun inside(at: Int, length: Int) = at >= start && at <= end - length
            if (!inside(start, 8)) return null
            val bigEndian = when {
                b.has(start, 'M', 'M') -> true
                b.has(start, 'I', 'I') -> false
                else -> return null
            }
            val offset = b.u32(start + 4, bigEndian)
            if (offset < 0 || offset > end - start) return null
            val ifd = start + offset
            if (!inside(ifd, 2)) return null
            for (n in 0 until b.u16(ifd, bigEndian)) {
                val entry = ifd + 2 + 12 * n
                if (!inside(entry, 12)) return null
                if (b.u16(entry, bigEndian) != TAG_ORIENTATION) continue
                return if (b.u16(entry + 2, bigEndian) == TIFF_TYPE_SHORT) b.u16(entry + 8, bigEndian) else null
            }
            return null
        }
    }
}
