package tether.ide.tools

import tether.cli.ToolOutput
import com.intellij.ide.scratch.ScratchUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.Callable

class ScratchFileToolTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).let { editors -> created.forEach(editors::closeFile) }
            ApplicationManager.getApplication().runWriteAction { created.forEach { it.delete(this) } }
        } finally {
            super.tearDown()
        }
    }

    private fun create(name: String, content: String? = null): ToolOutput {
        val dir = project.basePath!!
        val tool = ScratchFileTool(ChatIdeHost(project, dir) { dir })
        val args = buildJsonObject {
            put("name", JsonPrimitive(name))
            if (content != null) put("content", JsonPrimitive(content))
        }
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable { tool.call(args) })
        PlatformTestUtil.waitWithEventsDispatching("The tool did not finish", { future.isDone }, 30)
        return future.get()
    }

    private fun fileIn(out: ToolOutput): VirtualFile {
        val path = Regex("scratch file (\\S+) and").find(out.text)?.groupValues?.get(1) ?: error("no path in: ${out.text}")
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(path)!!.also { created += it }
    }

    fun testCreatesAScratchFileWithTheTextAndOpensIt() {
        val out = create("query.sql", "select 1;\n")
        assertFalse(out.text, out.isError)
        val file = fileIn(out)
        assertTrue(ScratchUtil.isScratch(file))
        assertEquals("select 1;\n", VfsUtilCore.loadText(file))
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(file))
    }

    fun testTheExtensionSetsTheLanguage() {
        val file = fileIn(create("notes.json", "{}"))
        assertEquals("JSON", PsiManager.getInstance(project).findFile(file)!!.language.id)
    }

    fun testATakenNameGetsANewFile() {
        val first = fileIn(create("same.txt", "one"))
        val second = fileIn(create("same.txt", "two"))
        assertFalse(first.path == second.path)
        assertEquals("one", VfsUtilCore.loadText(first))
    }

    fun testAPathIsRefused() {
        val out = create("../escape.txt")
        assertTrue(out.isError)
        assertTrue(out.text, out.text.contains("not a path"))
    }
}
