package tether.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorContextServiceTest : BasePlatformTestCase() {
    private val service get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private lateinit var savedDir: String
    private lateinit var savedSchedule: (Runnable) -> Unit
    private lateinit var savedFinder: (PsiFile, Document, Int) -> Pair<Symbol?, Symbol?>

    /** The context as a chat would get it with a symbol: waits for the background lookup (absolute path). */
    /** Moves the caret one step: a debounce round, which [setUp] made run inline. */
    private fun nudgeTheCaret() = myFixture.editor.caretModel.moveToOffset(myFixture.editor.caretModel.offset + 1)

    private fun capture(): EditorContext? {
        var result: EditorContext? = null
        var done = false
        service.resolve(null) { result = it; done = true }
        PlatformTestUtil.waitWithEventsDispatching("The symbol lookup did not finish", { done }, 10)
        return result
    }

    /** Lines 1-11; JS lists functions, classes, methods, top-level variables and object properties in its Structure View. */
    private val js = listOf(
        "function outer(a) {",
        "  const x = 1;",
        "  function inner() {",
        "    return x;",
        "  }",
        "  return inner();",
        "}",
        "class K {",
        "  m() { return 1; }",
        "}",
        "const conf = { a: 1 };",
    ).joinToString("\n", postfix = "\n")

    /** The light project and its services are shared between tests, so every editor is closed first. */
    override fun setUp() {
        super.setUp()
        manager.openFiles.forEach(manager::closeFile)
        savedDir = service.projectDir
        savedSchedule = service.schedule
        savedFinder = service.finder
        service.forgetCache()
        service.projectDir = root
        service.schedule = { it.run() }
    }

    override fun tearDown() {
        try {
            manager.openFiles.forEach(manager::closeFile)
            service.projectDir = savedDir
            service.schedule = savedSchedule
            service.finder = savedFinder
            service.forgetCache()
        } finally {
            super.tearDown()
        }
    }

    fun testCaretInANestedFunction() {
        myFixture.configureByText("calc.js", js.replace("return x;", "return <caret>x;"))
        val context = capture()!!
        assertEquals(myFixture.file.virtualFile.path, context.path)
        assertEquals(4, context.line)
        assertEquals(12, context.column)
        assertNull(context.selection)
        assertEquals(Symbol("inner", "function", Lines(3, 5)), context.symbol)
        assertNull(context.container)
    }

    fun testCaretInAClassMethod() {
        myFixture.configureByText("calc.js", js.replace("return 1;", "return <caret>1;"))
        assertEquals(Symbol("m", "method", Lines(9, 9)), capture()!!.symbol)
    }

    fun testCaretInAPropertyOutsideAnyFunction() {
        myFixture.configureByText("calc.js", js.replace("a: 1", "a: <caret>1"))
        val context = capture()!!
        assertEquals(Symbol("a", "property", Lines(11, 11)), context.symbol)
        assertNull(context.container)
    }

    fun testCaretOutsideEveryElement() {
        myFixture.configureByText("calc.js", "// notes<caret>\n\nfunction f() {}\n")
        assertNull(capture()!!.symbol)
    }

    fun testTypeScriptMethod() {
        myFixture.configureByText("c.ts", "export class C {\n  total(n: number): number {\n    return <caret>n;\n  }\n}\n")
        assertEquals(Symbol("total", "method", Lines(2, 4)), capture()!!.symbol)
    }

    fun testSelectionLines() {
        myFixture.configureByText("calc.js", js.replace("  const x", "<selection>  const x").replace("return x;", "return x;<caret></selection>"))
        assertEquals(Lines(2, 4), capture()!!.selection)
    }

    fun testSelectionEndingAtALineStartStopsOnThePreviousLine() {
        myFixture.configureByText("calc.js", js.replace("  const x = 1;\n", "<selection>  const x = 1;\n<caret></selection>"))
        assertEquals(Lines(2, 2), capture()!!.selection)
    }

    fun testPlainTextHasALocationButNoSymbol() {
        myFixture.configureByText("notes.txt", "one\ntw<caret>o\n")
        assertEquals(EditorContext(myFixture.file.virtualFile.path, 2, 3), capture())
    }

    fun testNoEditorNoContext() = assertNull(capture())

    fun testTheLastFocusedEditorWins() {
        myFixture.configureByText("a.txt", "a")
        val a = myFixture.editor
        manager.openFile(myFixture.addFileToProject("b.txt", "b").virtualFile, false)
        val b = (manager.getSelectedEditor(myFixture.findFileInTempDir("b.txt")) as TextEditor).editor
        service.editorFocused(b)
        assertTrue(capture()!!.path.endsWith("/b.txt"))
        service.editorFocused(a)
        assertTrue(capture()!!.path.endsWith("/a.txt"))
    }

    fun testEditorsWithoutALocalFileAreIgnored() {
        myFixture.configureByText("a.txt", "a")
        val factory = EditorFactory.getInstance()
        val scratch = factory.createEditor(factory.createDocument("scratch"), project)
        try {
            service.editorFocused(scratch)
            assertTrue(capture()!!.path.endsWith("/a.txt"))
        } finally {
            factory.releaseEditor(scratch)
        }
    }

    fun testCaretAndSelectionChangesNotifyListeners() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        var fired = 0
        service.addListener(testRootDisposable) { fired++ }
        myFixture.editor.caretModel.moveToOffset(5)
        assertTrue(fired > 0)
        val afterCaret = fired
        myFixture.editor.selectionModel.setSelection(0, 3)
        assertTrue(fired > afterCaret)
    }

    fun testEventsFromNonContextEditorsScheduleNothing() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        var fired = 0
        service.addListener(testRootDisposable) { fired++ }
        val factory = EditorFactory.getInstance()
        val console = factory.createViewer(myFixture.editor.document, project, EditorKind.CONSOLE)
        try {
            console.caretModel.moveToOffset(5)
            console.selectionModel.setSelection(0, 3)
            assertEquals(0, fired)
        } finally {
            factory.releaseEditor(console)
        }
    }

    fun testListenerIsRemovedWithItsParent() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        val parent = Disposer.newDisposable()
        var fired = 0
        service.addListener(parent) { fired++ }
        Disposer.dispose(parent)
        myFixture.editor.caretModel.moveToOffset(5)
        assertEquals(0, fired)
    }

    fun testContextForAChatUsesItsFolderOrTheCounterpart() {
        myFixture.addFileToProject(".claude/worktrees/wt1/src/a.js", "x")
        myFixture.openFileInEditor(myFixture.addFileToProject("src/a.js", "function f() {}\n").virtualFile)
        assertEquals("src/a.js", service.contextFor(root)!!.path)
        assertEquals("src/a.js", service.contextFor("$root/.claude/worktrees/wt1")!!.path)
        val worktreeNotCreatedYet = "$root/.claude/worktrees/wt2"
        assertEquals("the main-relative path", "src/a.js", service.contextFor(worktreeNotCreatedYet)!!.path)
        assertNull(service.contextFor("/elsewhere"))
        assertEquals("a.js", service.contextFor("$root/src")!!.path)
    }

    fun testAFileOutsideTheProjectIsNamedByItsAbsolutePath() {
        val scratch = myFixture.addFileToProject("scratches/scratch_1.txt", "IllegalStateException").virtualFile
        myFixture.openFileInEditor(scratch)
        service.projectDir = "$root/project"
        assertEquals(scratch.path, service.contextFor("$root/project")!!.path)
    }

    fun testAFailingStructureModelMeansNoSymbol() {
        assertEquals(null to null, SymbolFinder.safely { throw IllegalStateException("boom") })
        val symbol = Symbol("f", "function", Lines(1, 1))
        assertEquals(symbol to null, SymbolFinder.safely { symbol to null })
    }

    fun testDumbModeMeansNoSymbolButStillALocation() {
        myFixture.configureByText("calc.js", js.replace("return x;", "return <caret>x;"))
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val context = capture()!!
            assertEquals(4, context.line)
            assertNull(context.symbol)
        }
        assertNotNull(capture()!!.symbol)
    }

    fun testCaretPauseWarmsTheCacheEvenWithoutListeners() {
        myFixture.configureByText("calc.js", js.replace("return x;", "return <caret>x;"))
        val onEdt = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        service.finder = { file, document, offset ->
            onEdt += ApplicationManager.getApplication().isDispatchThread
            SymbolFinder.find(file, document, offset)
        }
        nudgeTheCaret()
        PlatformTestUtil.waitWithEventsDispatching("The cache never warmed", { service.contextFor(root)?.symbol != null }, 10)
        assertEquals("inner", service.contextFor(root)!!.symbol!!.name)
        assertFalse(onEdt.any { it })
    }

    fun testAThrowingListenerDoesNotStopTheOthers() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        var second = 0
        service.addListener(testRootDisposable) { throw IllegalStateException("boom") }
        service.addListener(testRootDisposable) { second++ }
        myFixture.editor.caretModel.moveToOffset(5)
        assertTrue(second > 0)
    }

    fun testSymbolsAreFoundOffTheEdtAndTheListenerOnlyReadsTheCache() {
        myFixture.configureByText("calc.js", js.replace("return x;", "return <caret>x;"))
        val onEdt = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        service.finder = { file, document, offset ->
            onEdt += ApplicationManager.getApplication().isDispatchThread
            SymbolFinder.find(file, document, offset)
        }
        val seen = mutableListOf<EditorContext?>()
        service.addListener(testRootDisposable) { seen += service.contextFor(root) }
        nudgeTheCaret()
        assertTrue("nothing ran on the EDT while the round returned", onEdt.isEmpty() || onEdt.none { it })
        assertNull("the listener got the location at once, without waiting for the lookup", seen.first()!!.symbol)
        PlatformTestUtil.waitWithEventsDispatching("The symbol never arrived", { seen.last()?.symbol != null }, 10)
        assertEquals("inner", seen.last()!!.symbol!!.name)
        assertTrue(onEdt.isNotEmpty())
        assertFalse(onEdt.any { it })
        val calls = onEdt.size
        service.contextFor(root)
        assertEquals("a refresh on a warm cache runs nothing", calls, onEdt.size)
    }

    /** The finder fails before the file opens, since the caret events of opening it warm the cache. */
    fun testAFailingLookupCompletesWithTheLocationOnly() {
        service.finder = { _, _, _ -> throw IllegalStateException("boom") }
        myFixture.configureByText("calc.js", js.replace("return x;", "return <caret>x;"))
        val context = capture()!!
        assertEquals(4, context.line)
        assertNull(context.symbol)
    }
}
