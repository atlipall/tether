package tether.ide.tools

import tether.cli.McpTool
import tether.cli.ToolOutput
import tether.cli.long
import tether.cli.str
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal enum class ArgType { STRING, INTEGER, BOOLEAN, STRINGS }

/** One argument of a tool. */
internal class Arg(val name: String, val type: ArgType, val description: String, val required: Boolean = true)

/** The arguments every tool that points at a symbol takes. */
internal val SYMBOL_ARGS = listOf(
    Arg("file_path", ArgType.STRING, "File containing the symbol, absolute or relative to the working directory."),
    Arg("line", ArgType.INTEGER, "1-based line where the symbol appears (its declaration or any use)."),
    Arg("symbol", ArgType.STRING, "The symbol's name exactly as written on that line."),
    Arg("column", ArgType.INTEGER, "1-based column, only needed when the name appears more than once on the line.", required = false),
)

/** A symbol as a tool's arguments name it ([SYMBOL_ARGS]). */
data class SymbolRef(val file: VirtualFile, val line: Int, val symbol: String, val column: Int?)

/** Base of the IDE's tools: a [ToolUnavailable] thrown by [run] becomes an error result carrying its message. */
internal abstract class IdeTool(protected val host: ChatIdeHost, private val args: List<Arg>) : McpTool {
    final override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (a in args) putJsonObject(a.name) {
                when (a.type) {
                    ArgType.STRINGS -> {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                    else -> put("type", a.type.name.lowercase())
                }
                put("description", a.description)
            }
        }
        putJsonArray("required") { args.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
    }

    final override fun call(args: JsonObject): ToolOutput =
        try { run(args) } catch (e: ToolUnavailable) { ToolOutput(e.message.orEmpty(), isError = true) }

    protected abstract fun run(args: JsonObject): ToolOutput

    protected fun JsonObject.string(key: String): String = optionalString(key) ?: throw ToolUnavailable("Give $key.")

    /** The trimmed text of argument [key]; null when it is missing or blank. */
    protected fun JsonObject.optionalString(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }

    protected fun JsonObject.int(key: String): Int = long(key)?.toInt() ?: throw ToolUnavailable("Give $key (a number).")

    /** The checkable file [key] names. */
    protected fun JsonObject.file(key: String = "file_path"): VirtualFile {
        val path = string(key)
        val file = host.resolve(path) ?: throw ToolUnavailable("No such file: $path")
        host.skipReason(file)?.let { throw ToolUnavailable("${host.display(file)}: $it") }
        return file
    }

    /** As [file], and in the chat's checkout: tools that change files never reach into another checkout. */
    protected fun JsonObject.fileInCheckout(key: String = "file_path"): VirtualFile =
        file(key).also { if (!host.inCheckout(it.path)) throw ToolUnavailable("${host.display(it)} is not in this chat's checkout.") }

    protected fun JsonObject.symbol(file: VirtualFile = file()) = SymbolRef(file, int("line"), string("symbol"), long("column")?.toInt())
}
