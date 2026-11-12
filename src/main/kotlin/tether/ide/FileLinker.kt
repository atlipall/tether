package tether.ide

import tether.git.isUnder
import tether.image.Images
import tether.session.FileDiff
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.vcsUtil.VcsUtil
import java.io.File
import java.io.IOException

data class ResolvedPath(val target: String, val line: Int?)

@Service(Service.Level.PROJECT)
class FileLinker(private val project: Project) {
    private val pattern = Regex("""^(.+?)(?:(?::|#L)(\d+)(?:[:-]\d+)?)?$""")

    /**
     * Resolves a path Claude mentioned. Relative paths are tried against [cwd] (the chat's folder) first, then
     * the project content roots, then as the end of a path anywhere in the project (see [onlyFileEndingWith]), so a
     * bare `charts.ts:587` links too. A file counts if it is project content or lies under [cwd].
     */
    fun resolve(candidate: String, cwd: String? = null): ResolvedPath? {
        val match = pattern.matchEntire(candidate.trim()) ?: return null
        val raw = match.groupValues[1]
        val line = match.groupValues[2].toIntOrNull()
        val file = findFile(raw, cwd) ?: onlyFileEndingWith(raw, cwd) ?: return null
        return ResolvedPath(file.url, line)
    }

    /**
     * The one project file whose path ends with the relative path [raw] (`charts.ts`, `lib/ChartManager.ts`), found
     * by name in the file index. When several match, those under [cwd] are preferred; if that still leaves more than
     * one the path is ambiguous and nothing is returned, rather than a link to the wrong file. Returns null while
     * the IDE is indexing.
     */
    private fun onlyFileEndingWith(raw: String, cwd: String?): VirtualFile? {
        val relative = raw.removePrefix("./")
        if (relative.isEmpty() || raw.startsWith("/") || hasParentSegment(relative)) return null
        val suffix = "/$relative"
        val matches = indexedFiles(relative.substringAfterLast('/')).filter { it.path.endsWith(suffix) }
        val preferred = matches.filter { isUnder(it.path, cwd) }.ifEmpty { matches }
        return preferred.singleOrNull()
    }

    /** The project content files called [name], from the file index; none while the IDE is indexing. */
    private fun indexedFiles(name: String): List<VirtualFile> = ReadAction.compute<List<VirtualFile>, RuntimeException> {
        if (project.isDisposed) return@compute emptyList()
        try {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).filterNot { it.isDirectory }
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    /**
     * A `data:` URL for a local image Claude's markdown shows (`![alt](path)`), or null. The file is found like [resolve]
     * finds one (relative to [cwd] first, then the content roots; it must be project content or lie under [cwd]); the
     * path may not contain a `..` segment (split on both slashes), a URL scheme or be over 1000 characters; the file, symbolic links resolved, must still lie under a content root or [cwd]; the file must be at most 5 MB and a PNG, JPEG, GIF or WebP by
     * its magic bytes. The page gets a display copy (at most 800 px and 512 KB). Reads the file: never call it on the EDT.
     */
    fun localImage(candidate: String, cwd: String?): String? {
        val file = imageFileAt(candidate, cwd) ?: return null
        if (file.length > Images.MAX_BYTES) return null
        val bytes = try { VfsUtilCore.loadBytes(file) } catch (e: IOException) { return null }
        if (bytes.size > Images.MAX_BYTES) return null
        return Images.preview(bytes)?.dataUrl()
    }

    /**
     * The file a markdown image's path names, found and limited as [localImage] describes, apart from its size and
     * type; null when there is none or the path is refused. Never call it on the EDT.
     */
    fun imageFileAt(candidate: String, cwd: String?): VirtualFile? {
        val raw = candidate.trim()
        if (raw.isEmpty() || raw.length > MAX_IMAGE_PATH || SCHEME.containsMatchIn(raw) || hasParentSegment(raw)) return null
        val file = findFile(raw, cwd) ?: return null
        return file.takeIf { resolvesInside(it, cwd) && !it.isDirectory }
    }

    /** Whether the file, with symbolic links resolved, lies under a (canonical) content root or the chat folder. */
    private fun resolvesInside(file: VirtualFile, cwd: String?): Boolean {
        val roots = ReadAction.compute<List<String>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        }
        return (roots + listOfNotNull(cwd)).any { isUnder(file.path, it) }
    }

    /**
     * The file [raw] names, from the VFS as it is; failing that, refreshed from disk (outside any read action), since
     * Claude may have just created it and the VFS may not know yet.
     */
    private fun findFile(raw: String, cwd: String?): VirtualFile? {
        val cached = ReadAction.compute<VirtualFile?, RuntimeException> { lookup(raw, cwd) }
        if (cached != null) return cached
        val relative = raw.removePrefix("./")
        val local = LocalFileSystem.getInstance()
        val roots = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.filter { it.fileSystem === local }
        }
        val paths = buildList {
            if (raw.startsWith("/")) {
                add(raw)
            } else {
                if (cwd != null) add("${cwd.trimEnd('/')}/$relative")
                roots.forEach { add("${it.path}/$relative") }
            }
        }
        val refreshed = paths.mapNotNull { local.refreshAndFindFileByPath(it) }
        return ReadAction.compute<VirtualFile?, RuntimeException> { refreshed.firstOrNull { accepts(it, cwd) } }
    }

    private fun lookup(raw: String, cwd: String?): VirtualFile? {
        val relative = raw.removePrefix("./")
        val candidates = buildList {
            if (raw.startsWith("/")) {
                LocalFileSystem.getInstance().findFileByPath(raw)?.let(::add)
            } else {
                folder(cwd)?.findFileByRelativePath(relative)?.let(::add)
                ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                    root.findFileByRelativePath(relative)?.let(::add)
                }
            }
        }
        return candidates.firstOrNull { accepts(it, cwd) }
    }

    /** The chat folder: on disk, or inside a content root that lives on another file system (tests use an in-memory one). */
    private fun folder(cwd: String?): VirtualFile? {
        if (cwd == null) return null
        LocalFileSystem.getInstance().findFileByPath(cwd)?.let { return it }
        val dir = cwd.trimEnd('/')
        return ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { root ->
            when {
                dir == root.path -> root
                dir.startsWith(root.path + "/") -> root.findFileByRelativePath(dir.removePrefix(root.path + "/"))
                else -> null
            }
        }
    }

    private fun accepts(file: VirtualFile, cwd: String?): Boolean =
        !file.isDirectory && (ProjectFileIndex.getInstance(project).isInContent(file) || isUnder(file.path, cwd))

    /** [target] is a VFS URL or an absolute local path; [line] is 1-based. */
    fun open(target: String, line: Int?) {
        val file = findByTarget(target) ?: return
        OpenFileDescriptor(project, file, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    fun showDiff(diff: FileDiff) {
        DiffManager.getInstance().showDiff(project, request(diff, "Claude's edit", "Before Claude's edit"))
    }

    /**
     * Shows the turn's file at [index]. A file the turn created opens in the editor, since a diff against nothing tells
     * nothing; one deleted since falls back to the diff. Otherwise one diff window opens at that file, and its arrows
     * step through the turn's other edited files (see [steppableDiffs]).
     */
    fun showTurnDiffs(diffs: List<FileDiff>, index: Int) {
        val clicked = diffs[index]
        if (clicked.isNew) findByTarget(clicked.filePath)?.let {
            OpenFileDescriptor(project, it).navigate(true)
            return
        }
        val (shown, shownIndex) = steppableDiffs(diffs, index)
        val chain = SimpleDiffRequestChain(shown.map { request(it, "This turn", "Before this turn") })
        chain.index = shownIndex
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }

    /**
     * A turn's files in the IDE's changes browser (a file list beside the diff), like the header's Diff. The files are
     * refreshed first, since Claude writes outside the IDE, so the current side is what is on disk now. A file created
     * and then deleted in the turn is left out.
     */
    fun showTurnChanges(diffs: List<FileDiff>) {
        val files = LocalFileSystem.getInstance()
        files.refreshIoFiles(diffs.map { File(it.filePath) }, false, false, null)
        val changes = diffs.mapNotNull { diff ->
            val path = VcsUtil.getFilePath(diff.filePath, false)
            val before = if (diff.isNew) null else TurnStartRevision(path, diff.originalContent)
            val after = files.findFileByPath(diff.filePath)?.let { CurrentContentRevision.create(path) }
            if (before == null && after == null) null else Change(before, after)
        }
        AbstractVcsHelper.getInstance(project).showWhatDiffersBrowser(changes, "Changes in this turn")
    }

    /** A file's text when the turn began. */
    private class TurnStartRevision(private val path: FilePath, private val text: String) : ContentRevision {
        override fun getContent(): String = text
        override fun getFile(): FilePath = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    /** [diff]'s original against the disk; a file deleted since shows as empty. */
    private fun request(diff: FileDiff, title: String, beforeLabel: String): SimpleDiffRequest {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(diff.filePath)
        val name = File(diff.filePath).name
        val factory = DiffContentFactory.getInstance()
        return SimpleDiffRequest(
            "$title: $name",
            factory.create(project, diff.originalContent, FileTypeManager.getInstance().getFileTypeByFileName(name)),
            if (file != null) factory.create(project, file) else factory.createEmpty(),
            beforeLabel,
            if (file != null) "Current" else "Deleted",
        )
    }

    private fun findByTarget(target: String): VirtualFile? =
        if (target.contains("://")) VirtualFileManager.getInstance().findFileByUrl(target)
        else LocalFileSystem.getInstance().refreshAndFindFileByPath(target)

    companion object {
        private const val MAX_IMAGE_PATH = 1000
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun getInstance(project: Project): FileLinker = project.service()

        /** Whether [path] has a `..` segment, split on both slashes. */
        private fun hasParentSegment(path: String): Boolean = path.split('/', '\\').any { it == ".." }

        /**
         * The diffs a turn's diff window steps through, the one at [clicked] and those of files the turn edited but
         * didn't create, with the clicked one's position among them.
         */
        internal fun steppableDiffs(diffs: List<FileDiff>, clicked: Int): Pair<List<FileDiff>, Int> {
            fun kept(i: Int) = i == clicked || !diffs[i].isNew
            return diffs.filterIndexed { i, _ -> kept(i) } to (0 until clicked).count(::kept)
        }
    }
}
