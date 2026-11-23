package tether.ide.tools

import tether.cli.McpTool
import tether.cli.ToolOutput
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.JsonObject

/** Longer than a read: these find, change and then re-check. */
private const val WRITE_TIMEOUT_MS = 45_000L

/** Most changed files re-checked after a change: the check is the slow part. */
private const val MAX_RECHECKED = 5

/** The tools that change files: each call asks for permission like Claude's own edits. */
internal fun writeTools(host: ChatIdeHost): List<McpTool> =
    listOf(ApplyFixTool(host), RenameTool(host), MoveFileTool(host), ScratchFileTool(host))

/** Base of the tools that change files: [change] does it; the answer adds the errors the IDE then reports. */
private abstract class WriteTool(host: ChatIdeHost, args: List<Arg>) : IdeTool(host, args) {
    /** What was done, and the changed files, most important first. */
    protected abstract fun change(args: JsonObject, deadline: Deadline): Pair<String, List<VirtualFile>>

    final override fun run(args: JsonObject): ToolOutput {
        val deadline = Deadline(WRITE_TIMEOUT_MS)
        val (done, changed) = change(args, deadline)
        return ToolOutput("$done\n${errorsIn(changed, deadline)}\nRe-read changed files before editing them.")
    }

    private fun errorsIn(files: List<VirtualFile>, deadline: Deadline): String {
        val checkable = files.filter { host.skipReason(it) == null }.take(MAX_RECHECKED)
        if (checkable.isEmpty()) return ""
        val found = try {
            Diagnostics.collect(host.project, checkable, HighlightSeverity.ERROR, deadline)
        } catch (e: ToolUnavailable) {
            return "(Not re-checked: ${e.message})"
        }
        val notes = checkable.mapNotNull { ProblemReport.now(host.display(it), found[it]?.problems.orEmpty()) }
        return notes.joinToString("\n").ifEmpty { "The IDE reports no errors in the changed files." }
    }

    protected fun names(files: List<VirtualFile>) = files.joinToString(", ", transform = host::display)
}

private class ApplyFixTool(host: ChatIdeHost) : WriteTool(host, listOf(
    Arg("file_path", ArgType.STRING, "The file, absolute or relative to the working directory."),
    Arg("line", ArgType.INTEGER, "1-based line of the problem."),
    Arg("fix", ArgType.STRING, "The fix's text exactly as the fixes tool listed it."),
)) {
    override val name = "apply_fix"
    override val description =
        "Applies one of the IDE's quick fixes (as listed by the fixes tool), saves the files it changed, and reports the errors left."

    override fun change(args: JsonObject, deadline: Deadline): Pair<String, List<VirtualFile>> {
        val applied = Fixes.apply(host.project, args.fileInCheckout(), args.int("line"), args.string("fix"), deadline)
        return "Applied “${applied.fix}”; changed ${names(applied.changed)}." to applied.changed
    }
}

private class RenameTool(host: ChatIdeHost) : WriteTool(host, SYMBOL_ARGS + Arg("new_name", ArgType.STRING, "The new name.")) {
    override val name = "rename_symbol"
    override val description =
        "Renames a symbol (variable, function, class, method, property, type...) and every reference to it across the " +
            "project with the IDE's rename refactoring, which follows imports, re-exports and aliases. Prefer it to " +
            "editing each use by hand. Point at the symbol on any line where it appears."

    override fun change(args: JsonObject, deadline: Deadline): Pair<String, List<VirtualFile>> {
        val newName = args.string("new_name")
        val renamed = Refactors.rename(host.project, args.symbol(args.fileInCheckout()), newName, host.checkoutScope(), deadline)
        val count = renamed.changed.size
        return "Renamed ${renamed.what} to `$newName`; $count file${if (count == 1) "" else "s"} changed: ${names(renamed.changed)}." to renamed.changed
    }
}

private class MoveFileTool(host: ChatIdeHost) : WriteTool(host, listOf(
    Arg("file_path", ArgType.STRING, "The file to move, absolute or relative to the working directory."),
    Arg("destination", ArgType.STRING, "Its new path including the file name (absolute or relative to the working directory); a new name renames it."),
)) {
    override val name = "move_file"
    override val description =
        "Moves and/or renames a file with the IDE's refactoring, updating the imports and other references to it across " +
            "the project. Prefer it to mv plus hand-edited imports."

    override fun change(args: JsonObject, deadline: Deadline): Pair<String, List<VirtualFile>> {
        val file = args.fileInCheckout()
        val from = host.display(file)
        val destination = host.absolute(args.string("destination"))
        if (!host.inCheckout(destination)) throw ToolUnavailable("$destination is not in this chat's checkout.")
        val moved = Refactors.move(host.project, file, destination, host.checkoutScope(), deadline)
        val updated = if (moved.updated.isEmpty()) "no references needed updating" else "updated references in ${names(moved.updated)}"
        return "Moved $from to ${host.display(moved.now)}; $updated." to listOf(moved.now) + moved.updated
    }
}
