package tether.ide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Finds project files for an `@` mention as the user types, the way Go to File does: the query's last segment
 * matches file names (camel humps and parts of words count), and any folders before it must appear, as whole
 * folder names, in the file's path inside its content root. An empty query offers the files open in the editor, most
 * recently used first. While the IDE is indexing only that empty query finds anything.
 */
object FileFinder {
    /**
     * At most [limit] files for [query], best matches first. Call it in a read action, ideally a non-blocking one:
     * the scan of the project's file names checks for cancellation, so a newer query or a write action can stop it.
     */
    fun find(project: Project, query: String, limit: Int): List<VirtualFile> {
        if (project.isDisposed) return emptyList()
        val trimmed = query.trim().trimStart('/')
        return try {
            if (trimmed.isEmpty()) openFiles(project, limit) else matching(project, trimmed, limit)
        } catch (_: IndexNotReadyException) {
            if (trimmed.isEmpty()) openFiles(project, limit) else emptyList()
        }
    }

    /** The open files, the most recently used first. */
    private fun openFiles(project: Project, limit: Int): List<VirtualFile> {
        val open = FileEditorManager.getInstance(project).openFiles.toSet()
        val recentFirst = EditorHistoryManager.getInstance(project).fileList.asReversed()
        return (recentFirst.filter { it in open } + open).distinct().filter(VirtualFile::isValid).take(limit)
    }

    private fun matching(project: Project, query: String, limit: Int): List<VirtualFile> {
        val folders = query.substringBeforeLast('/', "").lowercase()
        val scope = GlobalSearchScope.projectScope(project)
        val index = ProjectFileIndex.getInstance(project)
        return matchingNames(query.substringAfterLast('/'), scope)
            .asSequence()
            .flatMap { FilenameIndex.getVirtualFilesByName(it, scope).sortedBy { file -> file.path.length } }
            .filter { !it.isDirectory && inFolders(it, folders, index) }
            .take(limit)
            .toList()
    }

    /**
     * The file names that match [name], best first, each scored once. The index may also offer names with no file in
     * [scope] (a library's), so every match is kept for the caller to look up rather than a shortlist.
     */
    private fun matchingNames(name: String, scope: GlobalSearchScope): List<String> {
        val matcher = NameUtil.buildMatcher("*$name").build()
        val scored = ArrayList<Pair<Int, String>>()
        FilenameIndex.processAllFileNames({ candidate ->
            ProgressManager.checkCanceled()
            if (matcher.matches(candidate)) scored += matcher.matchingDegree(candidate) to candidate
            true
        }, scope, null)
        return scored.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * Whether [folders] (lower case, `/`-separated) are whole folders on [file]'s path inside its content root, so
     * `view` doesn't match `webview` and folders above the project never count; true for none.
     */
    private fun inFolders(file: VirtualFile, folders: String, index: ProjectFileIndex): Boolean {
        if (folders.isEmpty()) return true
        val root = index.getContentRootForFile(file) ?: return false
        val inside = file.parent?.let { VfsUtilCore.getRelativePath(it, root) } ?: return false
        return "/${inside.lowercase()}/".contains("/$folders/")
    }
}
