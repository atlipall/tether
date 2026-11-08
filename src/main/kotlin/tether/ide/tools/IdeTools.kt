package tether.ide.tools

import tether.cli.McpTool
import tether.follow.CheckoutMapper
import tether.follow.Placement
import tether.git.canonicalPath
import tether.git.isUnder
import tether.ide.Mention
import tether.session.IdeHost
import tether.settings.TetherSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import java.io.File

internal const val TOOL_TIMEOUT_MS = 30_000L
private const val AFTER_EDIT_TIMEOUT_MS = 20_000L

/**
 * The IDE tools and edit checks one chat's Claude gets; [cwd] is the chat's current folder. The bug report tool is
 * always lent; the others only while the IDE tools setting is on.
 */
class ChatIdeHost(internal val project: Project, private val projectDir: String, internal val cwd: () -> String) : IdeHost {
    private val settings get() = TetherSettings.getInstance().state

    override val tools: List<McpTool> get() =
        (if (settings.ideTools) readTools(this) + writeTools(this) else emptyList()) + BugReportTool(this)

    override val checksEdits: Boolean get() = settings.checkEdits

    override fun afterEdit(filePath: String): String? {
        val file = resolve(filePath) ?: return null
        if (skipReason(file) != null) return null
        val checked = try {
            Diagnostics.collect(project, listOf(file), HighlightSeverity.ERROR, Deadline(AFTER_EDIT_TIMEOUT_MS))[file]
        } catch (_: ToolUnavailable) {
            return null
        }
        return ProblemReport.afterEdit(display(file), checked?.problems.orEmpty())
    }

    /** [path] absolute or relative to the chat's folder, as the IDE knows it; null when there is no such file. */
    internal fun resolve(path: String): VirtualFile? =
        LocalFileSystem.getInstance().refreshAndFindFileByPath(absolute(path))?.takeIf { !it.isDirectory }

    /** [path] made absolute against the chat's folder. */
    internal fun absolute(path: String): String = File(path).let { if (it.isAbsolute) it else File(cwd(), path) }.path

    /** Whether [path] lies in the chat's checkout (for main, not in a worktree inside it). */
    internal fun inCheckout(path: String): Boolean {
        val mapper = CheckoutMapper(projectDir)
        return when (val placement = mapper.place(cwd())) {
            is Placement.Followable -> mapper.checkoutOf(path) == placement.checkout
            is Placement.Outside -> isUnder(path, placement.dir)
        }
    }

    /** Why the IDE can't check [file] meaningfully: outside the project's indexed content, highlighting reports bogus errors. */
    internal fun skipReason(file: VirtualFile): String? = when {
        file.fileType.isBinary -> "binary file, not checked"
        !ReadAction.compute<Boolean, RuntimeException> { !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(file) } ->
            "not checked: the file is outside the IDE project or excluded from indexing"
        else -> null
    }

    /** [file] relative to the chat's folder when inside it, else absolute. */
    internal fun display(file: VirtualFile): String {
        val path = canonicalPath(file.path)
        return Mention.relativeTo(path, canonicalPath(cwd())) ?: path
    }

    /** The folder of the chat's checkout (canonical): main, a worktree, or the chat's own folder outside the project. */
    internal fun checkoutDir(): String = when (val placement = CheckoutMapper(projectDir).place(cwd())) {
        is Placement.Followable -> placement.checkout.dir
        is Placement.Outside -> placement.dir
    }

    /** The project's files in the chat's checkout: a main chat does not see worktree copies, nor a worktree chat main's. */
    internal fun checkoutScope(): GlobalSearchScope {
        val inProject = GlobalSearchScope.projectScope(project)
        val placement = CheckoutMapper(projectDir).place(cwd())
        val dir = checkoutDir()
        val files = LocalFileSystem.getInstance()
        val root = files.findFileByPath(dir) ?: return inProject
        val scope = inProject.intersectWith(GlobalSearchScopesCore.directoryScope(project, root, true))
        if (placement !is Placement.Followable || !placement.checkout.isMain) return scope
        val worktrees = files.findFileByPath("$dir/${CheckoutMapper.WORKTREES}") ?: return scope
        return scope.intersectWith(GlobalSearchScope.notScope(GlobalSearchScopesCore.directoryScope(project, worktrees, true)))
    }
}
