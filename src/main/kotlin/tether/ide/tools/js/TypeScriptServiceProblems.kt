package tether.ide.tools.js

import tether.ide.tools.Deadline
import tether.ide.tools.FixCandidate
import tether.ide.tools.Problem
import tether.ide.tools.ProblemSource
import tether.ide.tools.Severity
import tether.ide.tools.ToolUnavailable
import com.intellij.lang.javascript.integration.JSAnnotationError
import com.intellij.lang.typescript.compiler.TypeScriptService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** The TypeScript service's errors for a file (type errors and the like), which the editor shows from its own pass. */
class TypeScriptServiceProblems : ProblemSource {
    override val name = "the TypeScript service"

    override fun start(project: Project, file: VirtualFile): CompletableFuture<List<Problem>> =
        highlight(project, file) { errors -> errors.mapNotNull { problemOf(it) } }

    /**
     * The service's fixes for its errors on 1-based [line]. Asked for in a non-blocking read, since the service may be
     * called here and that must not hold up the user's typing meanwhile.
     */
    override fun fixes(project: Project, file: VirtualFile, line: Int, deadline: Deadline): List<FixCandidate> {
        val pending = highlight(project, file) { it }
        val errors = deadline.await(pending, name).filter { it.line == line - 1 }
        if (errors.isEmpty()) return emptyList()
        return deadline.read("Asking $name for fixes") {
            val (service, psi) = highlightable(project, file) ?: return@read emptyList()
            val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@read emptyList()
            errors.flatMap { error ->
                val problem = problemOf(error) ?: return@flatMap emptyList()
                val offset = (document.getLineStartOffset(error.line) + error.column).coerceIn(0, document.textLength)
                val element = psi.findElementAt(offset) ?: return@flatMap emptyList()
                service.getServiceFixes(psi, element, error).map { FixCandidate(problem, offset, it) }
            }
        }
    }

    /**
     * Starts asking the service for [file]'s errors (this file's only), passed through [transform]. Returns at once;
     * the future gives no errors when the service doesn't handle [file]. Everything, including picking the service,
     * happens in a non-blocking read action. Picking the service can build the project's JS config graph through
     * `runBlockingCancellable`, which throws on a thread without a progress indicator, and the read supplies one. The
     * read steps aside for any write action and then asks again: it holds the read lock while tsserver works, and a
     * write waiting on that lock would stop the EDT from getting one too, freezing the IDE until tsserver answered.
     *
     * [transform] runs inside the request, so the future returned is the request's own: cancelling it cancels the
     * request, which a later `thenApply` stage would not. When the project closes first, the future fails with
     * [ToolUnavailable].
     */
    private fun <T> highlight(project: Project, file: VirtualFile, transform: (List<JSAnnotationError>) -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val request = ReadAction.nonBlocking(Callable { transform(serviceErrors(project, file)) })
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())
        request.onSuccess { result.complete(it) }.onError { result.completeExceptionally(asFailure(it)) }
        result.whenComplete { _, _ -> if (result.isCancelled) request.cancel() }
        return result
    }

    /** [error] as a failure for callers waiting on a request: its cancellation (the project closed) as [ToolUnavailable]. */
    private fun asFailure(error: Throwable): Throwable =
        if (error is CancellationException) ToolUnavailable("$name stopped: the project closed.") else error

    /** The service and PSI for [file] when the service can highlight it, else null. Read action. */
    private fun highlightable(project: Project, file: VirtualFile): Pair<TypeScriptService, PsiFile>? {
        if (project.isDisposed) return null
        val service = TypeScriptService.getForFile(project, file) ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return if (service.canHighlight(psi)) service to psi else null
    }

    /**
     * The service's errors for [file] alone. Non-blocking read action: waits for tsserver in ways a pending write
     * action cancels, and then cancels the service's request too, so a restarted read doesn't leave it running.
     */
    private fun serviceErrors(project: Project, file: VirtualFile): List<JSAnnotationError> {
        val (service, psi) = highlightable(project, file) ?: return emptyList()
        val pending = service.highlight(psi) ?: return emptyList()
        val errors = try {
            ProgressIndicatorUtils.awaitWithCheckCanceled(pending).orEmpty()
        } finally {
            pending.cancel(true)
        }
        return errors.filter { it.absoluteFilePath.let { p -> p == null || p == file.path } }
    }

    /** [error] as a 1-based [Problem] (the service counts lines and columns from 0); null when it has no message or severity. */
    private fun problemOf(error: JSAnnotationError): Problem? {
        val message = error.description?.takeIf { it.isNotBlank() } ?: return null
        val severity = Severity.of(error.severity) ?: return null
        return Problem(error.line + 1, error.column + 1, severity, message)
    }
}
