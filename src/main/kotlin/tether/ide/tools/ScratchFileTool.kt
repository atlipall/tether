package tether.ide.tools

import tether.cli.ToolOutput
import tether.cli.str
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import kotlinx.serialization.json.JsonObject

/**
 * Creates one of the IDE's scratch files (Scratches and Consoles, outside the project) and opens it in the editor, for
 * throwaway notes, queries and experiments that don't belong in the repository. A name already taken gets a numbered
 * one rather than replacing that file. It writes a file, so it asks for permission like Claude's own edits.
 */
internal class ScratchFileTool(host: ChatIdeHost) : IdeTool(host, listOf(
    Arg("name", ArgType.STRING, "The file name with an extension, which sets its language (e.g. query.sql, notes.md, try.ts)."),
    Arg("content", ArgType.STRING, "The file's text.", required = false),
)) {
    override val name = "create_scratch_file"
    override val description =
        "Creates a scratch file in the IDE (in Scratches and Consoles, outside the project and its repository) and opens " +
            "it in the editor for the user. Use it when the user asks for a scratch file, or for throwaway notes, queries " +
            "or experiments they want to see in the IDE but not commit. Returns the file's absolute path; read and edit " +
            "it there with the usual tools. A name already taken gets a numbered one instead."

    override fun run(args: JsonObject): ToolOutput {
        val fileName = args.string("name")
        if (fileName.contains('/') || fileName.contains('\\')) throw ToolUnavailable("Give a file name, not a path: $fileName")
        val text = args.str("content").orEmpty()
        val path = Deadline(TOOL_TIMEOUT_MS).onEdt(host.project) {
            val file = ScratchRootType.getInstance().createScratchFile(
                host.project, fileName, languageOf(fileName), text, ScratchFileService.Option.create_new_always,
            ) ?: throw ToolUnavailable("The IDE could not create the scratch file $fileName.")
            FileEditorManager.getInstance(host.project).openFile(file, false)
            file.path
        }
        return ToolOutput("Created the scratch file $path and opened it in the editor.")
    }

    private fun languageOf(fileName: String): Language =
        LanguageUtil.getFileTypeLanguage(FileTypeManager.getInstance().getFileTypeByFileName(fileName)) ?: PlainTextLanguage.INSTANCE
}
