package tether.image

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.intellij.util.io.URLUtil
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** The image formats the chat accepts. Always decided by the bytes, never by a file name or a reported MIME type. */
enum class ImageType(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp");

    companion object {
        fun sniff(b: ByteArray): ImageType? = when {
            b.has(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
            b.has(0, 0xFF, 0xD8, 0xFF) -> JPEG
            (b.has(0, 'G', 'I', 'F', '8', '7', 'a') || b.has(0, 'G', 'I', 'F', '8', '9', 'a')) -> GIF
            b.has(0, 'R', 'I', 'F', 'F') && b.has(8, 'W', 'E', 'B', 'P') -> WEBP
            else -> null
        }
    }
}

/** Image bytes whose [type] was checked by magic bytes. */
class Image(val type: ImageType, val bytes: ByteArray, val width: Int, val height: Int) {
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)
    fun dataUrl(): String = "data:${type.mime};base64,${base64()}"

    companion object {
        /**
         * The type and bytes of a `data:` URL this plugin built ([dataUrl]), or null when it is not one or its bytes
         * are not a supported image. Width and height are not read (0).
         */
        fun fromDataUrl(url: String): Image? {
            if (!url.startsWith("data:image/")) return null
            val bytes = URLUtil.getBytesFromDataUri(url) ?: return null
            val type = ImageType.sniff(bytes) ?: return null
            return Image(type, bytes, 0, 0)
        }
    }
}

sealed interface Prepared {
    class Ok(val image: Image) : Prepared
    class Rejected(val reason: String) : Prepared
}

/** Validation, downscaling and re-encoding of images (pure: no IDE API). */
object Images {
    const val MAX_PER_MESSAGE = 10
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_EDGE = 2000
    /** Largest raw input (before downscaling) the plugin reads from the page or a picked file. */
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    /** Never decode more pixels than this (about 160 MB of ARGB): a small file can claim a huge canvas. */
    const val MAX_PIXELS = 40_000_000L
    const val THUMB_EDGE = 160
    const val THUMB_BYTES = 64 * 1024
    const val PREVIEW_EDGE = 800
    const val PREVIEW_BYTES = 512 * 1024
    const val JPEG_QUALITY = 0.85f

    const val NOT_AN_IMAGE = "not a PNG, JPEG, GIF, WebP or HEIC image"
    const val UNREADABLE = "the image can't be read"
    const val TOO_MANY_PIXELS = "the image is too large to process"
    const val WEBP_TOO_LARGE = "WebP images over 5 MB or 2000 px can't be downscaled here"
    const val HEIC_UNCONVERTIBLE = "HEIC photos can't be converted here; install libheif or ImageMagick, or export them as JPEG"

    /**
     * Whether `ImageIO` can decode WebP in this JVM. The JDK cannot; inside the IDE the bundled WebP plugin
     * (`intellij.webp`) registers a reader. Without one, WebP is only ever passed through as it is. Tests replace it.
     */
    @Volatile internal var webpDecodable: () -> Boolean = { ImageIO.getImageReadersByMIMEType("image/webp").hasNext() }
    const val TOO_LARGE = "it can't be brought under 5 MB"

    /** Width and height from the header, without decoding pixels; null when the header can't be read. */
    fun dimensions(bytes: ByteArray, type: ImageType): Pair<Int, Int>? =
        if (type == ImageType.WEBP) webpDimensions(bytes) else try {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            null
        }

    /**
     * An image ready to send to Claude: as it is when it is at most [maxBytes] and its long edge at most [maxEdge];
     * otherwise scaled to fit [maxEdge] (aspect kept) and re-encoded — JPEG stays JPEG (quality 0.85), PNG/GIF become
     * PNG and fall back to JPEG when still too large. WebP is scaled like GIF when [webpDecodable], else refused.
     * A HEIC photo is first converted to JPEG ([Heic.jpegIfHeic]) and refused when it can't be, so it blocks: never
     * call it on the EDT. Even an image that fits is decoded in full, as a truncated or corrupt one would break the session.
     */
    fun prepare(input: ByteArray, maxBytes: Int = MAX_BYTES, maxEdge: Int = MAX_EDGE): Prepared {
        val bytes = Heic.jpegIfHeic(input) ?: return Prepared.Rejected(HEIC_UNCONVERTIBLE)
        val type = ImageType.sniff(bytes) ?: return Prepared.Rejected(NOT_AN_IMAGE)
        val (w, h) = dimensions(bytes, type) ?: return Prepared.Rejected(UNREADABLE)
        val fits = bytes.size <= maxBytes && max(w, h) <= maxEdge
        if (type == ImageType.WEBP && !webpDecodable()) {
            return if (fits) Prepared.Ok(Image(type, bytes, w, h)) else Prepared.Rejected(WEBP_TOO_LARGE)
        }
        if (w.toLong() * h > MAX_PIXELS) return Prepared.Rejected(TOO_MANY_PIXELS)
        val source = decode(bytes) ?: return Prepared.Rejected(UNREADABLE)
        if (fits) return Prepared.Ok(Image(type, bytes, w, h))
        return fit(source, type, maxEdge, maxBytes)?.let { Prepared.Ok(it) } ?: Prepared.Rejected(TOO_LARGE)
    }

    /**
     * A copy to show in the chat: the bytes themselves when they fit [maxBytes] and [maxEdge] (an undecodable WebP:
     * when it fits [maxBytes]; the page scales it), otherwise a scaled re-encoding as in [prepare]; null when nothing fits.
     */
    fun preview(bytes: ByteArray, maxEdge: Int = PREVIEW_EDGE, maxBytes: Int = PREVIEW_BYTES): Image? {
        val type = ImageType.sniff(bytes) ?: return null
        val (w, h) = dimensions(bytes, type) ?: return null
        val undecodable = type == ImageType.WEBP && !webpDecodable()
        if (bytes.size <= maxBytes && (undecodable || max(w, h) <= maxEdge)) return Image(type, bytes, w, h)
        if (undecodable || w.toLong() * h > MAX_PIXELS) return null
        val source = decode(bytes) ?: return null
        return fit(source, type, maxEdge, maxBytes)
    }

    /**
     * The decoded pixels, or null when the image cannot be read. A JPEG must reach its end-of-image marker
     * ([isCompleteJpeg]): the JPEG reader accepts a cut-off file with only a warning. ImageIO closes the stream.
     */
    private fun decode(bytes: ByteArray): BufferedImage? {
        if (ImageType.sniff(bytes) == ImageType.JPEG && !isCompleteJpeg(bytes)) return null
        return try {
            ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)))
        } catch (e: Exception) { null }
    }

    /**
     * Whether a JPEG reaches its end-of-image marker, found by walking its segments (so an EXIF thumbnail's own
     * markers don't count, and neither does a trailer after the end). Entropy-coded data holds only byte-stuffed FF00
     * and restart markers. Running out of bytes anywhere means the file is cut off.
     */
    internal fun isCompleteJpeg(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u(0) != 0xFF || u(1) != 0xD8) return false
        var i = 2
        fun skipFillBytes() {
            while (i < b.size && u(i) == 0xFF) i++
        }
        /** Moves past the entropy-coded data to the next real marker; false when the bytes run out first. */
        fun skipEntropyCodedData(): Boolean {
            while (true) {
                while (i < b.size && u(i) != 0xFF) i++
                if (i + 1 >= b.size) return false
                val next = u(i + 1)
                if (next == 0x00 || next in 0xD0..0xD7) i += 2 else return true
            }
        }
        while (true) {
            if (i >= b.size || u(i) != 0xFF) return false
            skipFillBytes()
            if (i >= b.size) return false
            val m = u(i)
            if (m == 0xD9) return true
            if (m in 0xD0..0xD7 || m == 0x01) { i++; continue }
            if (i + 2 >= b.size) return false
            val length = (u(i + 1) shl 8) or u(i + 2)
            if (length < 2 || i + 1 + length > b.size) return false
            i += 1 + length
            if (m == 0xDA && !skipEntropyCodedData()) return false
        }
    }

    private fun fit(source: BufferedImage, type: ImageType, maxEdge: Int, maxBytes: Int): Image? {
        val scale = minOf(1.0, maxEdge.toDouble() / max(source.width, source.height))
        val tw = max(1, (source.width * scale).roundToInt())
        val th = max(1, (source.height * scale).roundToInt())
        val scaled = resize(source, tw, th)
        if (type == ImageType.JPEG) return jpeg(scaled).takeIf { it.size <= maxBytes }?.let { Image(ImageType.JPEG, it, tw, th) }
        png(scaled).takeIf { it.size <= maxBytes }?.let { return Image(ImageType.PNG, it, tw, th) }
        return jpeg(scaled).takeIf { it.size <= maxBytes }?.let { Image(ImageType.JPEG, it, tw, th) }
    }

    /** Halves the image until one more halving would pass the target, then draws it at the target size (bilinear). */
    private fun resize(source: BufferedImage, tw: Int, th: Int): BufferedImage {
        var img = source
        do {
            val nw = if (img.width / 2 >= tw) img.width / 2 else tw
            val nh = if (img.height / 2 >= th) img.height / 2 else th
            img = draw(img, nw, nh, null)
        } while (nw != tw || nh != th)
        return img
    }

    private fun draw(img: BufferedImage, w: Int, h: Int, background: Color?): BufferedImage {
        val out = BufferedImage(w, h, if (background == null) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            if (background != null) {
                g.color = background
                g.fillRect(0, 0, w, h)
            }
            g.drawImage(img, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** PNG bytes, written through a memory-cached stream (as is [jpeg]): ImageIO's own default would buffer through a temp file on disk. */
    private fun png(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(out).use { stream -> check(ImageIO.write(img, "png", stream)) }
        return out.toByteArray()
    }

    /** JPEG has no alpha: transparent parts become white. */
    private fun jpeg(img: BufferedImage): ByteArray {
        val rgb = draw(img, img.width, img.height, Color.WHITE)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** VP8 (lossy), VP8L (lossless) and VP8X (extended) headers. */
    private fun webpDimensions(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        if (b.size < 30) return null
        return when {
            b.has(12, 'V', 'P', '8', ' ') && b.has(23, 0x9D, 0x01, 0x2A) -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
            b.has(12, 'V', 'P', '8', 'L') && u8(20) == 0x2F -> {
                val bits = u8(21).toLong() or (u8(22).toLong() shl 8) or (u8(23).toLong() shl 16) or (u8(24).toLong() shl 24)
                ((bits and 0x3FFF) + 1).toInt() to (((bits shr 14) and 0x3FFF) + 1).toInt()
            }
            b.has(12, 'V', 'P', '8', 'X') -> (le24(24) + 1) to (le24(27) + 1)
            else -> null
        }?.takeIf { (w, h) -> w > 0 && h > 0 }
    }
}

internal fun ByteArray.has(offset: Int, vararg expected: Any): Boolean {
    if (size < offset + expected.size) return false
    return expected.withIndex().all { (i, e) ->
        val want = when (e) {
            is Char -> e.code
            is Int -> e
            else -> error("byte or char expected")
        }
        (this[offset + i].toInt() and 0xFF) == want
    }
}
