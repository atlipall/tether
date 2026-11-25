package tether.session

import tether.cli.CliConnection
import tether.cli.CliEvent
import tether.cli.CliMessages
import tether.cli.CliParser
import tether.cli.ModelChoice
import tether.cli.ModelOption
import tether.cli.Models
import tether.cli.SessionLaunch

/**
 * A claude process started in [cwd] through [connect] only to ask which models it offers and which one, at what effort,
 * it runs when a chat picks none; it is stopped as soon as it has answered. It sends no message, so it makes no session.
 * [schedule] runs its argument once the probe has had long enough; a probe still waiting then gives up.
 */
class ModelProbe(
    private val connect: (SessionLaunch) -> CliConnection,
    private val cwd: String,
    private val schedule: (Runnable) -> Unit,
) {
    private val lock = Any()
    private var connection: CliConnection? = null
    private var options: List<ModelOption> = emptyList()
    private var report: ((ModelChoice?) -> Unit)? = null
    private var stopped = false

    /**
     * Starts the process and calls [done] exactly once: with what it runs, or with null when it can't be started, exits,
     * refuses a request, lists no models or takes too long. [done] runs on the process's reader thread, the scheduler's
     * thread, or this one for a failed launch. A probe already [stop]ped starts nothing and calls [done] at once.
     */
    fun start(done: (ModelChoice?) -> Unit) {
        val launch = synchronized(lock) { !stopped.also { if (!it) report = done } }
        if (!launch) return done(null)
        try {
            val conn = connect(SessionLaunch(resumeId = null, permissionMode = null, cwd = cwd))
            synchronized(lock) { connection = conn }
            conn.start(onLine = { line -> CliParser.parse(line).forEach(::handle) }, onExit = { _, _ -> finish(null) })
            conn.send(CliMessages.initialize(INIT_ID))
        } catch (e: Exception) {
            finish(null)
            return
        }
        schedule { finish(null) }
    }

    /** Stops the process, if it still runs, and reports nothing found; a probe not yet started will start nothing. */
    fun stop() {
        synchronized(lock) { stopped = true }
        finish(null)
    }

    private fun handle(event: CliEvent) {
        if (event !is CliEvent.ControlResponse) return
        if (!event.isSuccess) return finish(null)
        when (event.requestId) {
            INIT_ID -> askSettings(event.response?.let(Models::options).orEmpty())
            SETTINGS_ID -> finish(event.response?.let(Models::running)?.let { ModelChoice.of(options, null, it) })
        }
    }

    private fun askSettings(offered: List<ModelOption>) {
        if (offered.isEmpty()) return finish(null)
        val conn = synchronized(lock) { options = offered; connection } ?: return
        conn.send(CliMessages.getSettings(SETTINGS_ID))
    }

    /** Stops the process and reports [found], once; later calls do nothing. The report runs outside the lock. */
    private fun finish(found: ModelChoice?) {
        val (done, conn) = synchronized(lock) {
            val done = report ?: return
            report = null
            (done to connection).also { connection = null }
        }
        conn?.let { runCatching { it.stop() } }
        done(found)
    }

    private companion object {
        const val INIT_ID = "probe-init"
        const val SETTINGS_ID = "probe-settings"
    }
}
