package tether.report

import tether.cli.ClaudeBinary
import tether.settings.TetherSettings
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gathers bug reports from the running IDE. Everything here blocks (it reads the IDE log and runs `claude --version`),
 * so call it off the EDT.
 */
object BugReports {
    private const val VERSION_TIMEOUT_SECONDS = 5L
    private const val UNKNOWN = "unknown"
    private val LOG = logger<BugReports>()

    /** A report with [description] (null asks the reader for one) and [exception], if any, from the IDE as it is now. */
    fun collect(description: String?, exception: String? = null): BugReport =
        BugReport(description, environment(), recentLog(), exception)

    /**
     * Gathers a report with [description] and [exception] and opens it in the user's browser as a new GitHub issue
     * titled [title] (empty leaves the title for the user). False, with a warning in the IDE log, when that failed.
     */
    fun openIssue(title: String, description: String?, exception: String? = null): Boolean = try {
        BrowserUtil.browse(collect(description, exception).issueUrl(title))
        true
    } catch (e: Exception) {
        LOG.warn("Couldn't open a GitHub issue for a bug report", e)
        false
    }

    /**
     * How the agent [command] line is shown: only the program's file name, and whether arguments follow, since
     * arguments and leading `NAME=value` variables can hold keys. `none` for no command, `set` when there are only
     * variables.
     */
    internal fun agentProgram(command: List<String>): String {
        val programAt = command.indexOfFirst { '=' !in it }
        if (programAt < 0) return if (command.isEmpty()) "none" else "set"
        val name = command[programAt].substringAfterLast('/').substringAfterLast('\\')
        return if (programAt < command.lastIndex) "$name (with arguments)" else name
    }

    private fun environment(): ReportEnvironment {
        val settings = TetherSettings.getInstance().state
        val app = ApplicationInfo.getInstance()
        return ReportEnvironment(
            tether = PluginManager.getPluginByClass(BugReports::class.java)?.version ?: UNKNOWN,
            ide = "${app.fullApplicationName} (${app.build.asString()})",
            os = "${SystemInfo.getOsNameAndVersion()} (${SystemInfo.OS_ARCH})",
            claude = claudeVersion(settings.claudePath.orEmpty()),
            acpAgent = agentProgram(ParametersListUtil.parse(settings.acpCommand.orEmpty())),
            settings = settingsSummary(settings),
        )
    }

    /** The settings that change what Tether does, without the values of free-text ones, which can hold keys. */
    private fun settingsSummary(settings: TetherSettings.State): String = buildList {
        listOf(
            "IDE tools" to settings.ideTools,
            "edit checks" to settings.checkEdits,
            "editor context" to settings.attachEditorContext,
            "follow checkout" to settings.followCheckout,
        ).forEach { (name, on) -> add("$name ${if (on) "on" else "off"}") }
        settings.defaultMode?.takeIf { it.isNotBlank() }?.let { add("default mode $it") }
        if (!settings.extraArgs.isNullOrBlank()) add("extra CLI arguments set")
    }.joinToString(", ")

    /** Tether's recent entries in the IDE log; empty when it can't be read. */
    private fun recentLog(): List<String> = try {
        File(PathManager.getLogPath(), "idea.log").useLines { TetherLog.recent(it) }
    } catch (e: Exception) {
        LOG.info("Couldn't read the IDE log for a bug report: ${e.message}")
        emptyList()
    }

    /** What `claude --version` prints, `not found` when there is no claude, [UNKNOWN] when it doesn't answer. */
    private fun claudeVersion(configured: String): String {
        val env = EnvironmentUtil.getEnvironmentMap()
        val binary = ClaudeBinary.find(configured, env) ?: return "not found"
        return try {
            val process = ProcessBuilder(binary, "--version").redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .start()
            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return UNKNOWN
            }
            process.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull()?.takeIf { it.isNotEmpty() } ?: UNKNOWN
        } catch (_: Exception) {
            UNKNOWN
        }
    }
}
