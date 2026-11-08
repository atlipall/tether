package tether.ui

import tether.session.Chat
import com.intellij.openapi.Disposable
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.CefSettings
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

/**
 * JCEF browser hosting one chat's page, wired to a [ChatBridge] bound to that chat. The page follows IDE theme and
 * editor colour scheme changes, and is reloaded (a few times at most) when its renderer process dies; a reload of the
 * page itself is the only main-frame navigation allowed once it has loaded. Files dropped on the panel become
 * attachments through a Swing [FileDrop], as the off-screen browser passes no native drops to the page. The page's
 * console errors and warnings go to the IDE log through a [PageConsole].
 */
class ChatPanel(private val project: Project, private val chat: Chat, parent: Disposable) : Disposable {
    val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val bridge = ChatBridge(project, chat, ::post)
    @Volatile private var pageUrl: String? = null
    @Volatile private var disposed = false
    /** Reloads after a renderer crash so far; capped by [MAX_RELOADS] so a page that keeps crashing stays down. */
    @Volatile private var reloads = 0
    private val console = PageConsole()

    init {
        Disposer.register(parent, this)
        followTheme()
        FileDrop.install(browser.component, FileDrop(bridge::acceptsDrops, bridge::dropHover, bridge::attachDropped))

        query.addHandler { json ->
            ApplicationManager.getApplication().invokeLater({ if (!disposed) bridge.handle(json) }, project.disposed)
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            /** Installs the bridge on the first main-frame load, which is our own page; anything else never gets it. */
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                val url = cefBrowser.url
                val page = pageUrl ?: url.also { pageUrl = it }
                if (url != page) return
                val js = "window.__send = function(m) { ${query.inject("m")} }; window.dispatchEvent(new Event('bridge-ready'));"
                cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(cefBrowser: CefBrowser, level: CefSettings.LogSeverity, message: String, source: String, line: Int): Boolean {
                console.report(level, message, source, line)
                return false
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean,
            ): Boolean = frame.isMain && pageUrl.let { it != null && it != request.url }

            override fun onRenderProcessTerminated(cefBrowser: CefBrowser, status: CefRequestHandler.TerminationStatus, errorCode: Int, errorString: String) {
                if (disposed || reloads >= MAX_RELOADS) return
                reloads++
                ApplicationManager.getApplication().invokeLater({ if (!disposed) reloadPage() }, project.disposed)
            }
        }, browser.cefBrowser)
        browser.loadHTML(WebAssets.page())
    }

    /** Loads the page afresh, as a new first load: its URL becomes the one page allowed. The bridge then re-sends everything. */
    private fun reloadPage() {
        pageUrl = null
        console.reset()
        browser.loadHTML(WebAssets.page())
    }

    /** Re-sends the theme when the IDE's look and feel or editor colour scheme changes, until this panel is disposed. */
    private fun followTheme() {
        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { post(Theme.message()) })
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { post(Theme.message()) })
    }

    private fun post(json: String) {
        if (disposed) return
        browser.cefBrowser.executeJavaScript("window.chat && window.chat.receive($json);", "", 0)
    }

    override fun dispose() {
        disposed = true
        browser.component.dropTarget = null
        bridge.dispose()
        chat.detach(bridge)
        Disposer.dispose(query)
        Disposer.dispose(browser)
    }

    private companion object {
        const val MAX_RELOADS = 3
    }
}
