package tether.ide

import tether.image.Images
import tether.session.FileDiff
import tether.testutil.TestImages
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FileLinkerTest : BasePlatformTestCase() {
    private val linker get() = FileLinker.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    fun testResolvesRelativePathWithLineForms() {
        val file = myFixture.addFileToProject("src/foo.ts", "a\nb\nc\n").virtualFile
        assertEquals(ResolvedPath(file.url, null), linker.resolve("src/foo.ts"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts:2"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts:2:5"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts#L2-3"))
    }

    fun testBareFileNameResolvesToTheOnlyFileWithThatName() {
        val file = myFixture.addFileToProject("src/charts/charts.ts", "a\nb\n").virtualFile
        assertEquals(ResolvedPath(file.url, 587), linker.resolve("charts.ts:587"))
    }

    fun testTrailingPathResolvesToTheOnlyFileEndingWithIt() {
        val file = myFixture.addFileToProject("node/apiary/lib/ChartManager.ts", "x").virtualFile
        myFixture.addFileToProject("node/apiary/src/ChartManager.ts", "x")
        assertEquals(ResolvedPath(file.url, 177), linker.resolve("lib/ChartManager.ts:177"))
    }

    fun testAmbiguousFileNameDoesNotResolve() {
        myFixture.addFileToProject("a/util.ts", "x")
        myFixture.addFileToProject("b/util.ts", "x")
        assertNull(linker.resolve("util.ts:3"))
    }

    fun testAmbiguousFileNamePrefersTheOneUnderTheChatFolder() {
        myFixture.addFileToProject("a/util.ts", "x")
        val inChat = myFixture.addFileToProject("wt/b/util.ts", "x").virtualFile
        assertEquals(ResolvedPath(inChat.url, null), linker.resolve("util.ts", "$root/wt"))
    }

    fun testUnknownOrDirectoryDoesNotResolve() {
        myFixture.addFileToProject("src/bar.ts", "x")
        assertNull(linker.resolve("src/missing.ts"))
        assertNull(linker.resolve("src"))
        assertNull(linker.resolve(""))
    }

    fun testOpenNavigatesToFile() {
        val file = myFixture.addFileToProject("src/open.ts", "a\nb\n").virtualFile
        linker.open(file.url, 2)
        val editors = FileEditorManager.getInstance(project)
        assertEquals(file, editors.selectedFiles.first())
        assertEquals(1, editors.selectedTextEditor!!.caretModel.logicalPosition.line)
    }

    fun testRelativePathResolvesAgainstChatFolderFirst() {
        myFixture.addFileToProject("lib/x.txt", "project copy")
        val inWorktree = myFixture.addFileToProject(".claude/worktrees/wt1/lib/x.txt", "worktree copy").virtualFile
        assertEquals(ResolvedPath(inWorktree.url, 3), linker.resolve("lib/x.txt:3", "$root/.claude/worktrees/wt1"))
    }

    fun testFallsBackToContentRootsWhenNotInChatFolder() {
        val inProject = myFixture.addFileToProject("lib/y.txt", "x").virtualFile
        assertEquals(ResolvedPath(inProject.url, null), linker.resolve("lib/y.txt", "$root/.claude/worktrees/wt1"))
    }

    fun testFileOutsideProjectResolvesOnlyUnderChatFolder() {
        val dir = Files.createTempDirectory("wt-outside").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        try {
            val notes = File(dir, "notes.txt").apply { writeText("x") }
            val url = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(notes)!!.url
            assertEquals(ResolvedPath(url, null), linker.resolve("notes.txt", dir.path))
            assertEquals(ResolvedPath(url, 2), linker.resolve("${notes.path}:2", dir.path))
            assertNull(linker.resolve("notes.txt", null))
            assertNull(linker.resolve(notes.path, null))
            assertNull(linker.resolve(notes.path, "/elsewhere"))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testSiblingFolderWithSamePrefixIsNotUnderChatFolder() {
        val parent = Files.createTempDirectory("wt-sib").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, parent.path)
        try {
            val proj = File(parent, "proj").apply { mkdirs() }
            val proj2 = File(parent, "proj2").apply { mkdirs() }
            val other = File(proj2, "a.txt").apply { writeText("x") }
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(other)!!
            assertNull(linker.resolve(other.path, proj.path))
            assertNull(linker.resolve("../proj2/a.txt", proj.path))
        } finally {
            parent.deleteRecursively()
        }
    }

    fun testSymlinkedChatFolderCountsAsUnder() {
        val parent = Files.createTempDirectory("wt-link").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, parent.path)
        try {
            val real = File(parent, "real").apply { mkdirs() }
            val f = File(real, "n.txt").apply { writeText("x") }
            val link = File(parent, "link")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            val url = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!.url
            assertEquals(ResolvedPath(url, null), linker.resolve(f.path, link.path))
        } finally {
            parent.deleteRecursively()
        }
    }

    // --- local images in replies ---

    private val blueUrl = "data:image/png;base64,${TestImages.BLUE_PNG_BASE64}"

    private fun binary(path: String, bytes: ByteArray): VirtualFile {
        val file = myFixture.addFileToProject(path, "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(bytes) }
        return file
    }

    fun testLocalImageInTheProjectBecomesADataUrl() {
        binary("docs/blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        assertEquals(blueUrl, linker.localImage("docs/blue.png", root))
        assertEquals(blueUrl, linker.localImage("./docs/blue.png", null))
    }

    fun testLocalImageIsFoundInTheChatsFolderFirst() {
        binary("shot.png", TestImages.solid(2, 2, "jpeg"))
        binary(".claude/worktrees/wt1/shot.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        assertEquals(blueUrl, linker.localImage("shot.png", "$root/.claude/worktrees/wt1"))
        assertTrue(linker.localImage("shot.png", root)!!.startsWith("data:image/jpeg;base64,"))
    }

    fun testNonImagesMissingFilesUrlsAndParentSegmentsAreRefused() {
        binary("docs/blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        myFixture.addFileToProject("docs/notes.png", "not really a png")
        assertNull(linker.localImage("docs/notes.png", root))
        assertNull(linker.localImage("docs/missing.png", root))
        assertNull(linker.localImage("docs", root))
        assertNull(linker.localImage("docs/../docs/blue.png", root))
        assertNull(linker.localImage("https://example.com/blue.png", root))
        assertNull(linker.localImage("file://$root/docs/blue.png", root))
        assertNull(linker.localImage("", root))
    }

    fun testLocalImageOver5MbIsRefused() {
        val big = ByteArray(Images.MAX_BYTES + 1).also { TestImages.bytes(TestImages.BLUE_PNG_BASE64).copyInto(it) }
        binary("docs/big.png", big)
        assertNull(linker.localImage("docs/big.png", root))
    }

    fun testLocalImageOutsideTheProjectOnlyUnderTheChatFolder() {
        val dir = Files.createTempDirectory("img-outside").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        try {
            val png = File(dir, "a.png").apply { writeBytes(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(png)!!
            assertEquals(blueUrl, linker.localImage("a.png", dir.path))
            assertEquals(blueUrl, linker.localImage(png.path, dir.path))
            assertNull(linker.localImage(png.path, null))
            assertNull(linker.localImage(png.path, root))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testBackslashParentSegmentsAndVeryLongPathsAreRefused() {
        binary("docs/blue.png", TestImages.bytes(TestImages.BLUE_PNG_BASE64))
        assertNull(linker.localImage("docs\\..\\docs\\blue.png", root))
        assertNull(linker.localImage("docs/..\\docs/blue.png", root))
        assertNull(linker.localImage("docs/" + "a/".repeat(500) + "blue.png", root))
    }

    fun testSymlinkInsideTheProjectPointingOutsideIsRefused() {
        val inside = Files.createTempDirectory("img-root").toFile().canonicalFile
        val outside = Files.createTempDirectory("img-secret").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, inside.path, outside.path)
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(inside)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        try {
            File(inside, "own.png").writeBytes(TestImages.bytes(TestImages.BLUE_PNG_BASE64))
            File(outside, "secret.png").writeBytes(TestImages.bytes(TestImages.BLUE_PNG_BASE64))
            Files.createSymbolicLink(File(inside, "link.png").toPath(), File(outside, "secret.png").toPath())
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(outside, "secret.png"))
            assertEquals(blueUrl, linker.localImage("own.png", null))
            assertNull(linker.localImage("link.png", null))
            assertNull(linker.localImage("link.png", inside.path))
        } finally {
            PsiTestUtil.removeContentEntry(module, rootFile)
            inside.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    fun testATurnsNewFileOpensInTheEditor() {
        val dir = Files.createTempDirectory("turn-new").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        try {
            val created = File(dir, "new.txt").apply { writeText("x") }
            val edited = File(dir, "old.txt").apply { writeText("y") }
            linker.showTurnDiffs(listOf(FileDiff(edited.path, "z"), FileDiff(created.path, "", isNew = true)), 1)
            assertEquals(created.path, FileEditorManager.getInstance(project).selectedFiles.first().path)
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testTheTurnDiffWindowStepsOnlyThroughEditedFilesAndTheClickedOne() {
        val a = FileDiff("/p/a.txt", "a")
        val b = FileDiff("/p/b.txt", "", isNew = true)
        val c = FileDiff("/p/c.txt", "c")
        assertEquals(listOf(a, c) to 1, FileLinker.steppableDiffs(listOf(a, b, c), 2))
        assertEquals(listOf(a, b, c) to 1, FileLinker.steppableDiffs(listOf(a, b, c), 1))
    }
}
