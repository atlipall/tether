package tether.git

import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Runs git with these arguments in a folder and returns its stdout, or null when git is missing or fails. */
typealias GitRunner = (dir: File, args: List<String>) -> String?

/** Runs git in [dir] and returns stdout; null if git is missing, fails, or takes longer than [timeoutSeconds]. */
fun runGit(dir: File, vararg args: String, timeoutSeconds: Long = 5): String? =
    runGitBytes(dir, *args, timeoutSeconds = timeoutSeconds)?.toString(Charsets.UTF_8)

/** The default [GitRunner]: [runGit] with a 30-second timeout (diffs of large checkouts take a while). */
fun gitRunner(dir: File, args: List<String>): String? = runGit(dir, *args.toTypedArray(), timeoutSeconds = 30)

/** A [GitRunner] for quick reads that a waiting message depends on: [runGit] with its short default timeout. */
fun quickGitRunner(dir: File, args: List<String>): String? = runGit(dir, *args.toTypedArray())

/** Trimmed stdout, or null when git failed or printed nothing. */
fun GitRunner.output(dir: File, vararg args: String): String? = this(dir, args.toList())?.trim()?.takeIf { it.isNotEmpty() }

/** The full hash of the commit [rev] names in [dir], or null when it names none. */
fun GitRunner.commit(dir: File, rev: String): String? = output(dir, "rev-parse", "--verify", "-q", "$rev^{commit}")

/** Runs the blocking reads and writes of child processes' pipes, so they never tie up the shared ForkJoin pool. */
private val pipeThreads = Executor { Thread.ofVirtual().name("tether-process-pipe").start(it) }

/**
 * Like [runGit], but returns stdout as bytes (file contents); [env] is added to git's environment, [input] is its stdin.
 * Optional locks are off, so a background diff never holds the index lock while Claude's own git needs it.
 */
fun runGitBytes(
    dir: File, vararg args: String, timeoutSeconds: Long = 5, env: Map<String, String> = emptyMap(), input: ByteArray? = null,
): ByteArray? = runProcessBytes(
    listOf("git", *args), dir, timeoutSeconds,
    mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_OPTIONAL_LOCKS" to "0") + env, input,
)

/**
 * Runs [command] in [dir] and returns its stdout, or null when it can't start, exits non-zero or takes longer than
 * [timeoutSeconds]. [env] is added to the IDE's environment, [input] is its stdin; stderr is discarded. Stdout is read
 * and stdin written on other threads, so a hung process (a lock, a prompt) still times out and it can fill stdout
 * before it has read all of stdin.
 */
fun runProcessBytes(
    command: List<String>, dir: File, timeoutSeconds: Long, env: Map<String, String> = emptyMap(), input: ByteArray? = null,
): ByteArray? = try {
    val builder = ProcessBuilder(command)
        .directory(dir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.environment().putAll(env)
    val process = builder.start()
    val out = CompletableFuture.supplyAsync({ process.inputStream.readBytes() }, pipeThreads)
    if (input == null) process.outputStream.close()
    else CompletableFuture.runAsync({ try { process.outputStream.use { it.write(input) } } catch (_: IOException) {} }, pipeThreads)
    when {
        !process.waitFor(timeoutSeconds, TimeUnit.SECONDS) -> { process.destroyForcibly(); null }
        process.exitValue() != 0 -> null
        else -> out.get(timeoutSeconds, TimeUnit.SECONDS)
    }
} catch (e: Exception) {
    if (e is InterruptedException) Thread.currentThread().interrupt()
    null
}
