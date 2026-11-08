package tether.image

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.math.roundToInt

/**
 * HEIC and HEIF photos, as iPhones and recent Macs take them. Claude doesn't accept them and the JVM can't decode
 * them, so an attached one is converted to JPEG by a converter the system has: `sips` on macOS, otherwise libheif's
 * `heif-convert` or ImageMagick when either is on the `PATH`. Those apply the file's rotation and mirroring, so the
 * JPEG is upright; `sips` and ImageMagick also shrink one larger than [Images.MAX_EDGE] to fit it, so even a 48 MP
 * photo stays under [Images.MAX_PIXELS]. The generic `mif1`/`msf1` brands are taken too, so an AVIF using one goes the same way. No IDE
 * API; runs external tools.
 */
object Heic {
    /** How long one conversion may take before its converter is stopped. */
    private const val TIMEOUT_SECONDS = 30L

    /** The `ftyp` major brands of HEIF still images and sequences, HEVC-coded or not. */
    private val BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1")

    private val QUALITY = (Images.JPEG_QUALITY * 100).roundToInt().toString()
    private val EDGE = Images.MAX_EDGE.toString()

    /** How far into the file [longEdge] looks for image sizes; iPhones put the `meta` box at the start. */
    private const val SIZE_SEARCH_BYTES = 1024 * 1024

    /**
     * The converters, in the order tried, each given the input and output file and whether the photo must shrink.
     * `sips -Z` would also enlarge a smaller photo, so it is only passed when the photo is too large.
     */
    private val CONVERTERS: List<(Path, Path, Boolean) -> List<String>> = listOf(
        { input, output, shrink ->
            listOf("sips") + (if (shrink) listOf("-Z", EDGE) else emptyList()) +
                listOf("-s", "format", "jpeg", "-s", "formatOptions", QUALITY, input.toString(), "--out", output.toString())
        },
        { input, output, _ -> listOf("heif-convert", "-q", QUALITY, input.toString(), output.toString()) },
        { input, output, _ -> listOf("magick", input.toString(), "-resize", "${EDGE}x$EDGE>", "-quality", QUALITY, output.toString()) },
    )

    /** What running one converter came to. */
    private sealed interface Attempt {
        /** The converter isn't installed: try the next one. */
        data object Missing : Attempt
        /** It ran and failed, timed out or was interrupted: trying more would only take longer. */
        data object Failed : Attempt
        class Converted(val bytes: ByteArray) : Attempt
    }

    /** Turns HEIC bytes into JPEG bytes, or null when no converter could. Tests replace it. */
    @Volatile internal var converter: (ByteArray) -> ByteArray? = ::convertWithSystemTool

    /** Whether [b] is an ISO media file whose major brand is a HEIF one, by its `ftyp` box. */
    fun matches(b: ByteArray): Boolean = b.has(4, 'f', 't', 'y', 'p') && String(b, 8, 4, Charsets.US_ASCII) in BRANDS

    /**
     * [bytes] as they are when they are not HEIC ([matches]); otherwise the photo as a JPEG, or null when it can't be
     * converted or the converter's output is not a JPEG. Blocks while converting: never call it on the EDT.
     */
    fun jpegIfHeic(bytes: ByteArray): ByteArray? =
        if (!matches(bytes)) bytes else converter(bytes)?.takeIf { ImageType.sniff(it) == ImageType.JPEG }

    /**
     * The longest edge among the image sizes (`ispe` properties) in the first [SIZE_SEARCH_BYTES], or 0 when none is
     * found. The full image is the largest: the others are its tiles and thumbnail.
     */
    internal fun longEdge(b: ByteArray): Int {
        fun u32(i: Int) = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        return (0..minOf(b.size, SIZE_SEARCH_BYTES) - 16)
            .filter { b.has(it, 'i', 's', 'p', 'e') }
            .maxOfOrNull { maxOf(u32(it + 8), u32(it + 12)) } ?: 0
    }

    /**
     * Writes [bytes] to a temporary folder and runs [CONVERTERS] there, moving on only past ones that aren't
     * installed. The folder is removed after.
     */
    private fun convertWithSystemTool(bytes: ByteArray): ByteArray? {
        val dir = try { Files.createTempDirectory("tether-heic-") } catch (e: IOException) { return null }
        try {
            val input = dir.resolve("photo.heic").also { it.writeBytes(bytes) }
            val output = dir.resolve("photo.jpg")
            val shrink = longEdge(bytes) > Images.MAX_EDGE
            for (command in CONVERTERS) {
                when (val attempt = runConverter(command(input, output, shrink), output)) {
                    Attempt.Missing -> continue
                    Attempt.Failed -> return null
                    is Attempt.Converted -> return attempt.bytes
                }
            }
            return null
        } catch (e: IOException) {
            return null
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Runs [command] and reads what it wrote to [output]. The process never outlives the call. */
    private fun runConverter(command: List<String>, output: Path): Attempt {
        output.deleteIfExists()
        val process = try {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: IOException) {
            return Attempt.Missing
        }
        return try {
            process.outputStream.close()
            if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) Attempt.Converted(output.readBytes())
            else Attempt.Failed
        } catch (e: IOException) {
            Attempt.Failed
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Attempt.Failed
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}
