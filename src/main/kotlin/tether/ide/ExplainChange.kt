package tether.ide

import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.VirtualFile

/** Lines a change covers on each side, 0-based with exclusive ends; a side with no lines is where lines were added or removed. */
data class ChangedLines(val beforeStart: Int, val beforeEnd: Int, val afterStart: Int, val afterEnd: Int) {
    /** Whether [line] on [side] is one of this change's lines. */
    fun contains(side: Side, line: Int): Boolean =
        if (side == Side.LEFT) line in beforeStart until beforeEnd else line in afterStart until afterEnd

    companion object {
        /** The change in [changes] that [line] on [side] belongs to, if any. */
        fun at(changes: List<ChangedLines>, side: Side, line: Int): ChangedLines? = changes.firstOrNull { it.contains(side, line) }

        /** Where unchanged [line] on [side] sits on the after side, shifted by the [changes] (in order) above it. */
        fun afterLineOf(changes: List<ChangedLines>, side: Side, line: Int): Int {
            if (side == Side.RIGHT) return line
            val above = changes.lastOrNull { it.beforeEnd <= line } ?: return line
            return line - above.beforeEnd + above.afterEnd
        }

        fun of(fragment: LineFragment) = ChangedLines(fragment.startLine1, fragment.endLine1, fragment.startLine2, fragment.endLine2)
    }
}

/** What "Explain This Change" asks about: a hunk it can quote, or a line whose history Claude has to dig up. */
sealed interface ChangeToExplain {
    val path: String

    /** Lines that differ between [before] and [after]; [firstLine]..[lastLine] are 1-based on the after side. */
    data class Hunk(
        override val path: String,
        val firstLine: Int,
        val lastLine: Int,
        val before: List<String>,
        val after: List<String>,
        val origin: Origin,
    ) : ChangeToExplain {
        companion object {
            /** The hunk [lines] covers in [beforeDoc] → [afterDoc]; removed lines point at the after-side line they left. */
            fun of(path: String, lines: ChangedLines, beforeDoc: Document, afterDoc: Document, origin: Origin) = Hunk(
                path = path,
                firstLine = lines.afterStart + 1,
                lastLine = maxOf(lines.afterEnd, lines.afterStart + 1),
                before = DiffUtil.getLines(beforeDoc, lines.beforeStart, lines.beforeEnd),
                after = DiffUtil.getLines(afterDoc, lines.afterStart, lines.afterEnd),
                origin = origin,
            )
        }
    }

    /** An unchanged, 1-based [line]: its last commit is the change. */
    data class CommittedLine(override val path: String, val line: Int) : ChangeToExplain

    /** Where a hunk was seen. */
    sealed interface Origin {
        /** In a diff whose sides the IDE titled [beforeTitle] and [afterTitle], typically revisions or branches. */
        data class Diff(val beforeTitle: String? = null, val afterTitle: String? = null) : Origin

        /** In an editor, as edits not yet committed. */
        data object Uncommitted : Origin
    }
}

/**
 * Editor and diff popup menus: asks the selected Tether chat why the change at the caret was made. In a diff that is the
 * hunk at the caret; in a file, the uncommitted change on the caret's line, or else the commit that last touched it.
 */
class ExplainChangeAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val viewer = e.getData(DiffDataKeys.DIFF_VIEWER)
        e.presentation.isVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.isEnabled = e.presentation.isVisible && (viewer == null || DiffCaret.fileOf(viewer) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val located = locate(project, e) ?: return
        TetherTarget.withChat(project) { chat ->
            chat.send(TetherMessages.explainChange(located.at(TetherTarget.pathFor(project, chat, located.file))))
        }
    }

    /** The change at the caret, read now, before the Tether window takes focus. */
    private fun locate(project: Project, e: AnActionEvent): LocatedChange? {
        e.getData(DiffDataKeys.DIFF_VIEWER)?.let { return DiffCaret.locate(it) }
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return FileCaret.locate(project, editor, file)
    }
}

/** A change found in [file], waiting for the chat to say what [file] is called there. */
private class LocatedChange(val file: VirtualFile, val at: (path: String) -> ChangeToExplain) {
    companion object {
        /** [hunk], already read from its documents, renamed to the chat's path for [file] once that is known. */
        fun of(file: VirtualFile, hunk: ChangeToExplain.Hunk) = LocatedChange(file) { path -> hunk.copy(path = path) }
    }
}

/** The path a hunk carries until the chat names its file. */
private const val UNNAMED = ""

/** Finds the change at the caret of a side-by-side or unified diff, from the changes the viewer itself shows. */
private object DiffCaret {
    /** The file [viewer] compares, preferring the after side; null for viewers it can't explain or diffs of unsaved text. */
    fun fileOf(viewer: Any): VirtualFile? {
        val contents = contentsOf(viewer) ?: return null
        return fileOf(contents.second) ?: fileOf(contents.first)
    }

    /** The hunk at [viewer]'s caret, or the history of its line when the caret is on an unchanged one. */
    fun locate(viewer: Any): LocatedChange? {
        val (before, after) = contentsOf(viewer) ?: return null
        val file = fileOf(after) ?: fileOf(before) ?: return null
        val (side, line) = caretOf(viewer) ?: return null
        val changes = changesOf(viewer) ?: return null
        val hunk = ChangedLines.at(changes, side, line)
            ?: return LocatedChange(file) { path -> ChangeToExplain.CommittedLine(path, ChangedLines.afterLineOf(changes, side, line) + 1) }
        val titles = titlesOf(viewer)
        val origin = ChangeToExplain.Origin.Diff(titles.getOrNull(0), titles.getOrNull(1))
        return LocatedChange.of(file, ChangeToExplain.Hunk.of(UNNAMED, hunk, before.document, after.document, origin))
    }

    private fun contentsOf(viewer: Any): Pair<DocumentContent, DocumentContent>? {
        val contents = requestOf(viewer)?.contents ?: return null
        val before = contents.getOrNull(0) as? DocumentContent ?: return null
        val after = contents.getOrNull(1) as? DocumentContent ?: return null
        return before to after
    }

    private fun requestOf(viewer: Any): ContentDiffRequest? = when (viewer) {
        is SimpleDiffViewer -> viewer.request
        is UnifiedDiffViewer -> viewer.request
        else -> null
    }

    private fun titlesOf(viewer: Any): List<String?> = requestOf(viewer)?.contentTitles.orEmpty()

    /** The caret as a 0-based line on one side of the comparison. */
    private fun caretOf(viewer: Any): Pair<Side, Int>? = when (viewer) {
        is SimpleDiffViewer -> viewer.currentSide to viewer.currentEditor.caretModel.logicalPosition.line
        is UnifiedDiffViewer -> {
            val line = viewer.editor.caretModel.logicalPosition.line
            Side.entries.firstNotNullOfOrNull { side -> viewer.transferLineToOnesideStrict(side, line).takeIf { it >= 0 }?.let { side to it } }
        }
        else -> null
    }

    /** The changes [viewer] highlights, honouring its whitespace settings; null while it is still comparing. */
    private fun changesOf(viewer: Any): List<ChangedLines>? = when (viewer) {
        is SimpleDiffViewer -> viewer.diffChanges.map { ChangedLines.of(it.fragment) }
        is UnifiedDiffViewer -> viewer.diffChanges?.map { ChangedLines.of(it.lineFragment) }
        else -> null
    }

    private fun fileOf(content: DiffContent): VirtualFile? =
        (content as? DocumentContent)?.let { it.highlightFile ?: FileDocumentManager.getInstance().getFile(it.document) }
}

/** Finds the change at the caret of an ordinary file editor. */
private object FileCaret {
    /** The uncommitted change on the caret's line, or the line's history when it has none. */
    fun locate(project: Project, editor: Editor, file: VirtualFile): LocatedChange {
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val tracker = LineStatusTrackerManager.getInstance(project).getLineStatusTracker(document)
        val range = tracker?.takeIf { it.isOperational() }?.getRangeForLine(line)
            ?: return LocatedChange(file) { path -> ChangeToExplain.CommittedLine(path, line + 1) }
        val lines = ChangedLines(range.vcsLine1, range.vcsLine2, range.line1, range.line2)
        return LocatedChange.of(file, ChangeToExplain.Hunk.of(UNNAMED, lines, tracker.vcsDocument, document, ChangeToExplain.Origin.Uncommitted))
    }
}
