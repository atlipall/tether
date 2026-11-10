package tether.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEventMulticasterEx
import com.intellij.openapi.editor.ex.FocusChangeListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import org.jetbrains.annotations.TestOnly
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Knows where the user is looking. The context editor is the text editor of this project that last had focus (focus
 * moving into the chat keeps it), or the selected text editor before any had focus; only editors of local files count.
 * Listeners hear, debounced, when the caret, selection, focused editor or selected tab changed. EDT only.
 */
@Service(Service.Level.PROJECT)
class EditorContextService(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastFocusedRef: WeakReference<Editor>? = null
    private var lastFocused: Editor?
        get() = lastFocusedRef?.get()
        set(value) { lastFocusedRef = value?.let(::WeakReference) }

    /** The symbol lookup (tests record the thread it runs on). Called only inside the background read action. */
    var finder: (PsiFile, Document, Int) -> Pair<Symbol?, Symbol?> = SymbolFinder::find

    /** Where lookups run. */
    var executor: Executor = AppExecutorUtil.getAppExecutorService()

    /** How long a send waits for the symbol before going out with the location only. */
    var lookupTimeoutMs: Long = 2000

    private data class ContextKey(val path: String, val stamp: Long, val offset: Int, val selectionStart: Int, val selectionEnd: Int)
    private class Cheap(val file: VirtualFile, val key: ContextKey, val offset: Int, val line: Int, val column: Int, val selection: Lines?)
    private class Found(val symbol: Symbol?, val container: Symbol?)
    private class Cached(val key: ContextKey, val found: Found)

    /** The symbols for the last state they were found for. Written on the EDT only. */
    @Volatile private var cached: Cached? = null
    private val inflight = HashMap<ContextKey, MutableList<(Found?) -> Unit>>()

    /** The main checkout's folder, for counterpart paths (tests point it at the light project's content root). */
    var projectDir: String = project.basePath ?: System.getProperty("user.home")

    /** Runs a round of listener calls once the editor has been quiet for [DEBOUNCE_MS] (tests run it at once). */
    var schedule: (Runnable) -> Unit = { round ->
        alarm.cancelAllRequests()
        alarm.addRequest(round, DEBOUNCE_MS)
    }

    init {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = changed(event.editor)
        }, this)
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = changed(e.editor)
        }, this)
        (multicaster as? EditorEventMulticasterEx)?.addFocusChangeListener(object : FocusChangeListener {
            override fun focusGained(editor: Editor) = editorFocused(editor)
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = tabSelected(event.newEditor)
        })
    }

    /**
     * A tab was selected without focus moving (a tab switch, or v3 swapping editors to another checkout): its text
     * editor becomes the context editor; a non-text tab means no context.
     */
    private fun tabSelected(selected: FileEditor?) {
        lastFocused = (selected as? TextEditor)?.editor?.takeIf(::accepts)
        changed(null)
    }

    /** [editor] got focus: it becomes the context editor if it is a text editor of a local file in this project. */
    fun editorFocused(editor: Editor) {
        if (!accepts(editor)) return
        lastFocused = editor
        changed(editor)
    }

    /** [listener] runs on the EDT after the context may have changed. Removed when [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * The cheap part of the context plus the symbol only if the cache holds it for the current caret, selection and text;
     * never walks PSI, so it is safe on the EDT (listeners and sends read this). Null without a context editor or a name for the
     * file in [cwd] ([ContextPaths.forNote]). Does not commit the document: only a lookup start needs that.
     */
    fun contextFor(cwd: String): EditorContext? {
        val cheap = cheap(commit = false) ?: return null
        val path = pathFor(cheap, cwd) ?: return null
        return build(cheap, path, cached?.takeIf { it.key == cheap.key }?.found)
    }

    /**
     * [contextFor] with the symbol: [done] runs on the EDT, at once when the cache holds the symbol for the current state,
     * else after a background read action finds it. A failed, dumb-mode or timed-out ([lookupTimeoutMs]) lookup completes
     * with the location only. A null [cwd] keeps the absolute path (tests). [done] gets null without a context editor.
     */
    fun resolve(cwd: String?, done: (EditorContext?) -> Unit) {
        val cheap = cheap() ?: return done(null)
        val path = if (cwd == null) cheap.file.path else pathFor(cheap, cwd) ?: return done(null)
        cached?.takeIf { it.key == cheap.key }?.let { return done(build(cheap, path, it.found)) }
        val finished = AtomicBoolean()
        var timeout: ScheduledFuture<*>? = null
        val finish = { found: Found? ->
            if (finished.compareAndSet(false, true)) {
                timeout?.cancel(false)
                done(build(cheap, path, found))
            }
        }
        timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater({ finish(null) }, ModalityState.defaultModalityState())
        }, lookupTimeoutMs, TimeUnit.MILLISECONDS)
        lookup(cheap, finish)
    }

    /** Forgets the cached symbol. */
    @TestOnly
    fun forgetCache() {
        cached = null
    }

    private fun contextEditor(): Editor? =
        lastFocused?.takeIf(::accepts) ?: FileEditorManager.getInstance(project).selectedTextEditor?.takeIf(::accepts)

    private fun pathFor(cheap: Cheap, cwd: String): String? =
        ContextPaths.forNote(cheap.file.path, cwd, projectDir) { cheap.file.fileSystem.findFileByPath(it) != null }

    private fun build(cheap: Cheap, path: String, found: Found?) =
        EditorContext(path, cheap.line, cheap.column, cheap.selection, found?.symbol, found?.container)

    private fun keyOf(editor: Editor, file: VirtualFile): ContextKey {
        val caret = editor.caretModel.primaryCaret
        return ContextKey(file.path, editor.document.modificationStamp, caret.offset, caret.selectionStart, caret.selectionEnd)
    }

    /**
     * What the EDT can read without touching the PSI structure: editor, file, caret, selection and the text's stamp.
     * With [commit] the document is committed first, since the background lookup reads committed PSI. EDT only.
     */
    private fun cheap(commit: Boolean = true): Cheap? {
        val editor = contextEditor() ?: return null
        val file = localFile(editor) ?: return null
        val document = editor.document
        if (commit) PsiDocumentManager.getInstance(project).commitDocument(document)
        val caret = editor.caretModel.primaryCaret
        val offset = caret.offset
        val line = document.getLineNumber(offset)
        val selection = if (caret.hasSelection()) selectedLines(caret, document) else null
        return Cheap(file, keyOf(editor, file), offset, line + 1, offset - document.getLineStartOffset(line) + 1, selection)
    }

    /** The lines [caret]'s selection covers; a selection that ends at the start of a line stops on the line before it. */
    private fun selectedLines(caret: Caret, document: Document): Lines {
        val start = caret.selectionStart
        val last = if (caret.selectionEnd > start) caret.selectionEnd - 1 else start
        return Lines(document.getLineNumber(start) + 1, document.getLineNumber(last) + 1)
    }

    /** The key of the context editor's state right now, without committing; null without a context editor. EDT only. */
    private fun currentKey(): ContextKey? {
        val editor = contextEditor() ?: return null
        return keyOf(editor, localFile(editor) ?: return null)
    }

    /**
     * Starts the background read action for [cheap] unless one for the same state runs; [waiter] hears the result on the
     * EDT (null on failure). A result for the state the editor is still in is cached, and the listeners hear a second
     * round so they learn the symbol.
     */
    private fun lookup(cheap: Cheap, waiter: (Found?) -> Unit) {
        inflight[cheap.key]?.let { it += waiter; return }
        inflight[cheap.key] = mutableListOf(waiter)
        val complete = { found: Found? ->
            val waiters = inflight.remove(cheap.key).orEmpty()
            val store = found != null && !DumbService.isDumb(project) && currentKey() == cheap.key
            if (store) cached = Cached(cheap.key, found!!)
            waiters.forEach { w -> try { w(found) } catch (e: Exception) { if (e is ControlFlowException) throw e; logger<EditorContextService>().warn("A context lookup waiter failed", e) } }
            if (store) listeners.forEach(::notify)
        }
        val file = cheap.file
        val offset = cheap.offset
        ReadAction.nonBlocking(Callable { findSymbol(file, offset) })
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState(), complete)
            .submit(executor)
            .onError {
                ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) complete(null) }, ModalityState.defaultModalityState())
            }
    }

    /** Background read action body: the PSI comes from the file here, never across threads. */
    private fun findSymbol(file: VirtualFile, offset: Int): Found {
        val psi = if (file.isValid) PsiManager.getInstance(project).findFile(file) else null
        val document = psi?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        val (symbol, container) = if (psi != null && document != null) {
            finder(psi, document, offset.coerceIn(0, document.textLength))
        } else {
            null to null
        }
        return Found(symbol, container)
    }

    /** File tabs are MAIN_EDITOR; light-test editors are UNTYPED. Diff, console and preview editors never count. */
    private fun accepts(editor: Editor): Boolean =
        !editor.isDisposed && editor.project === project &&
            (editor.editorKind == EditorKind.MAIN_EDITOR || editor.editorKind == EditorKind.UNTYPED) &&
            localFile(editor) != null

    private fun localFile(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.isValid && it.isInLocalFileSystem }

    /**
     * [editor] (or, when null, the selected tab) changed: after the debounce the listeners hear the cheap part at once,
     * and a second round follows when the symbol is found. Editors [accepts] rejects (consoles, diffs, previews) never
     * change the context.
     */
    private fun changed(editor: Editor?) {
        if (editor != null && !accepts(editor)) return
        schedule(Runnable {
            if (project.isDisposed) return@Runnable
            warm()
            listeners.forEach(::notify)
        })
    }

    /** Starts the background symbol lookup for the current state unless the cache has it. EDT only. */
    private fun warm() {
        val cheap = cheap() ?: return
        if (cached?.key != cheap.key) lookup(cheap) {}
    }

    private fun notify(listener: () -> Unit) {
        try {
            listener()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            logger<EditorContextService>().warn("An editor-context listener failed", e)
        }
    }

    override fun dispose() = Unit

    companion object {
        const val DEBOUNCE_MS = 150

        fun getInstance(project: Project): EditorContextService = project.service()
    }
}
