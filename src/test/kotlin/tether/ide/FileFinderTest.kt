package tether.ide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FileFinderTest : BasePlatformTestCase() {
    private fun find(query: String, limit: Int = 20) = FileFinder.find(project, query, limit)

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/ChatBridge.kt", "")
        myFixture.addFileToProject("src/ChatPanel.kt", "")
        myFixture.addFileToProject("src/webview/chat.js", "")
        myFixture.addFileToProject("test/ChatBridgeTest.kt", "")
        myFixture.addFileToProject("README.md", "")
    }

    fun testFilesAreFoundByPartOfTheirName() {
        assertEquals(setOf("ChatBridge.kt", "ChatBridgeTest.kt"), find("bridge").map { it.name }.toSet())
    }

    fun testCamelHumpsMatchLikeTheIdesGoToFile() {
        assertEquals(listOf("ChatPanel.kt"), find("CPan").map { it.name })
    }

    fun testCloserMatchesComeFirst() {
        assertEquals("ChatBridge.kt", find("ChatBridge").first().name)
    }

    fun testADirectoryInTheQueryNarrowsByPath() {
        assertEquals(listOf("chat.js"), find("webview/chat").map { it.name })
        assertEquals(listOf("ChatBridgeTest.kt"), find("test/Chat").map { it.name })
    }

    fun testFoldersInTheQueryMatchWholeFolderNames() {
        assertEmpty(find("view/chat"))
    }

    fun testTheLimitIsKept() {
        assertEquals(2, find("chat", limit = 2).size)
    }

    fun testAnEmptyQueryOffersTheOpenFilesNewestFirst() {
        val readme = myFixture.findFileInTempDir("README.md")
        val panel = myFixture.findFileInTempDir("src/ChatPanel.kt")
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(readme, false)
        manager.openFile(panel, true)
        try {
            assertEquals(listOf("ChatPanel.kt", "README.md"), find("").map { it.name })
        } finally {
            manager.openFiles.forEach(manager::closeFile)
        }
    }

    fun testNothingMatchingFindsNothing() {
        assertEmpty(find("zzzz"))
    }
}
