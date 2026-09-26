package dev.cerbos.epdp

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.core.os.HandlerCompat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Owns the hidden [WebView] that runs the Cerbos engine.
 *
 * The page, `bridge.js` and `server.wasm` are served from app assets on a private `https` origin.
 * Calls go in through `evaluateJavascript` (arguments passed as JSON literals). Results come back
 * through the `cerbosHost.postResult` JavaScript interface and are decoded on the JavaBridge
 * thread, so they don't queue behind UI work. Events come back through `cerbosHost.postMessage` and
 * are handled on the main thread.
 */
@MainThread
internal class CerbosWebViewHost(context: Context) {
    companion object {
        /** Reserved host that never resolves; every request to it is answered from assets. */
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val ASSET_DIRECTORY = "cerbos-epdp"
        const val PATH_PREFIX = "/$ASSET_DIRECTORY/"
        const val PAGE_URL = "$ORIGIN${PATH_PREFIX}index.html"
        const val HOST_OBJECT_NAME = "cerbosHost"

        private val files =
            mapOf(
                "index.html" to "text/html",
                "bridge.js" to "text/javascript",
                "server.wasm" to "application/wasm",
                "manifest.json" to "application/json",
            )
        private val requiredFiles = listOf("index.html", "bridge.js", "server.wasm")
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler: Handler = HandlerCompat.createAsync(Looper.getMainLooper())

    var onEvent: ((BridgeEvent) -> Unit)? = null
    var onProcessTerminated: (() -> Unit)? = null

    var webView: WebView? = null
        private set

    var isBridgeReady: Boolean = false
        private set

    /** Incremented per page; messages from an older page are ignored. */
    @Volatile private var generation = 0
    private var readySignal: CompletableDeferred<Unit>? = null
    private val pendingCalls = ConcurrentHashMap<String, PendingCall<*>>()
    private var callCounter = 0L

    private class PendingCall<T>(val result: CompletableDeferred<T>, val decode: (String) -> T) {
        fun complete(envelopeJSON: String) {
            try {
                result.complete(decode(envelopeJSON))
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }
    }

    /** Checks that the assets built by `CerbosBridge/build.mjs` are packaged. */
    fun verifyBundledResources(): CerbosException? {
        val present =
            try {
                appContext.assets.list(ASSET_DIRECTORY)?.toSet() ?: emptySet()
            } catch (_: IOException) {
                emptySet()
            }
        val missing = requiredFiles.filterNot { it in present }
        if (missing.isEmpty()) return null
        return CerbosException.WebView(
            "bundled web resources are missing (${missing.joinToString()}); run `npm run build` in CerbosBridge"
        )
    }

    /** Creates the web view if needed and waits for the bridge script to be ready. */
    suspend fun start(timeout: Duration = 30.seconds) {
        if (webView != null && isBridgeReady) return
        shutdown()

        val pageGeneration = ++generation
        val webView =
            try {
                WebView(appContext)
            } catch (error: RuntimeException) {
                // No WebView provider installed, or it's being updated.
                throw CerbosException.WebView("WebView is unavailable: ${error.message ?: error}")
            }
        configure(webView, pageGeneration)
        this.webView = webView
        isBridgeReady = false
        val ready = CompletableDeferred<Unit>()
        readySignal = ready

        CerbosLog.info("Loading Cerbos embedded PDP bridge page")
        webView.loadUrl(PAGE_URL)
        try {
            withTimeout(timeout) { ready.await() }
        } catch (_: TimeoutCancellationException) {
            if (readySignal === ready) readySignal = null
            throw CerbosException.Timeout("load the bridge page")
        }
    }

    /** Tears down the web view. [start] builds a fresh one. */
    fun shutdown() {
        failReady(CerbosException.WebView("shut down"))
        failPendingCalls(CerbosException.WebView("shut down"))
        isBridgeReady = false
        val webView = webView ?: return
        this.webView = null
        generation++
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.removeJavascriptInterface(HOST_OBJECT_NAME)
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView, pageGeneration: Int) {
        with(webView.settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkImage = true
            loadsImagesAutomatically = false
        }
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect > Cerbos embedded PDP
        }
        webView.addJavascriptInterface(HostObject(this, pageGeneration), HOST_OBJECT_NAME)
        webView.webViewClient = Client(pageGeneration)
        webView.webChromeClient = ChromeClient()
    }

    private fun failReady(error: CerbosException) {
        val ready = readySignal ?: return
        readySignal = null
        ready.completeExceptionally(error)
    }

    private fun failPendingCalls(error: CerbosException) {
        if (pendingCalls.isEmpty()) return
        val calls = pendingCalls.values.toList()
        pendingCalls.clear()
        calls.forEach { it.result.completeExceptionally(error) }
    }

    /**
     * Invokes a bridge method and returns its result, decoded by [decode] on the thread that
     * receives it. A call that gets no answer within [timeout] throws [CerbosException.Timeout].
     */
    suspend fun <T> call(
        method: String,
        paramsJSON: String?,
        timeout: Duration?,
        decode: (String) -> T,
    ): T {
        val webView = webView
        if (webView == null || !isBridgeReady) throw CerbosException.NotReady

        val key = "call-${++callCounter}"
        val id = BridgeJson.jsLiteral(key)
        val deferred = CompletableDeferred<T>()
        pendingCalls[key] = PendingCall(deferred, decode)
        // No result callback: errors come back through postResult like any other result.
        val params = paramsJSON?.let(BridgeJson::jsLiteral) ?: "null"
        webView.evaluateJavascript(
            "try{CerbosBridge.invoke($id,${BridgeJson.jsLiteral(method)},$params)}" +
                "catch(e){cerbosHost.postResult($id,JSON.stringify({ok:false,error:{name:'BridgeError',message:String(e)}}))}",
            null,
        )
        try {
            return if (timeout == null) deferred.await()
            else withTimeout(timeout) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            pendingCalls.remove(key)
            throw CerbosException.Timeout(method)
        } catch (error: CancellationException) {
            pendingCalls.remove(key)
            throw error
        }
    }

    /** Answers a `jwtDecode` request from the bridge. */
    fun resolveCallback(id: String, payloadJSON: String?, errorMessage: String?) {
        val webView = webView ?: return
        val script =
            "window.CerbosBridge.resolveCallback(${BridgeJson.jsLiteral(id)}, ${errorMessage == null}, " +
                "${BridgeJson.jsLiteral(payloadJSON ?: errorMessage ?: "")});"
        webView.evaluateJavascript(script, null)
    }

    private fun receiveEvent(pageGeneration: Int, json: String) {
        if (pageGeneration != generation) return
        val event =
            try {
                BridgeEvent.decode(json)
            } catch (error: CerbosException) {
                CerbosLog.error("Failed to decode bridge event: ${error.message}")
                return
            }
        if (event is BridgeEvent.BridgeReady) {
            isBridgeReady = true
            val ready = readySignal
            readySignal = null
            ready?.complete(Unit)
        }
        onEvent?.invoke(event)
    }

    /** Runs on the JavaBridge thread. */
    private fun receiveResult(pageGeneration: Int, id: String, envelopeJSON: String) {
        if (pageGeneration != generation) return
        val pending = pendingCalls.remove(id)
        if (pending == null) {
            CerbosLog.debug("Discarded the result of a bridge call that already timed out")
            return
        }
        pending.complete(envelopeJSON)
    }

    private fun handleRenderProcessGone(pageGeneration: Int, detail: RenderProcessGoneDetail?) {
        if (pageGeneration != generation) return
        val reason =
            if (detail?.didCrash() == true) "renderer process crashed"
            else
                "renderer process was killed by the system (priority ${detail?.rendererPriorityAtExit()})"
        CerbosLog.error("Web view $reason")
        isBridgeReady = false
        failReady(CerbosException.WebView(reason))
        failPendingCalls(CerbosException.WebView(reason))
        // A WebView is unusable once its renderer is gone.
        webView?.let { dead ->
            webView = null
            generation++
            dead.removeJavascriptInterface(HOST_OBJECT_NAME)
            dead.destroy()
        }
        onProcessTerminated?.invoke()
    }

    /** Injected as `window.cerbosHost`. Chromium calls these on the JavaBridge thread. */
    private class HostObject(host: CerbosWebViewHost, private val pageGeneration: Int) {
        private val host = WeakReference(host)

        @JavascriptInterface
        fun postMessage(json: String) {
            val host = host.get() ?: return
            host.mainHandler.post { host.receiveEvent(pageGeneration, json) }
        }

        @JavascriptInterface
        fun postResult(id: String, envelopeJSON: String) {
            host.get()?.receiveResult(pageGeneration, id, envelopeJSON)
        }
    }

    private inner class Client(private val pageGeneration: Int) : WebViewClient() {
        /** Serves the bundled assets. Runs on a Chromium IO thread. */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url
            if (url.scheme != "https" || "$ORIGIN".removePrefix("https://") != url.host) return null
            val path = url.path ?: return notFound(url.toString())
            if (!path.startsWith(PATH_PREFIX)) return notFound(url.toString())
            val name = path.removePrefix(PATH_PREFIX)
            val mimeType = files[name] ?: return notFound(url.toString())
            val stream =
                try {
                    appContext.assets.open("$ASSET_DIRECTORY/$name")
                } catch (_: IOException) {
                    return notFound(url.toString())
                }
            return WebResourceResponse(
                mimeType,
                null,
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                stream,
            )
        }

        private fun notFound(url: String): WebResourceResponse {
            CerbosLog.error("Bridge resource not found: $url")
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.toString() != PAGE_URL

        override fun onPageFinished(view: WebView, url: String?) {
            CerbosLog.debug("Bridge page loaded")
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame || pageGeneration != generation) return
            failReady(CerbosException.WebView("${error.description} (${error.errorCode})"))
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (!request.isForMainFrame || pageGeneration != generation) return
            failReady(
                CerbosException.WebView("HTTP ${errorResponse.statusCode} loading the bridge page")
            )
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            handleRenderProcessGone(pageGeneration, detail)
            return true
        }
    }

    private class ChromeClient : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val text = "[bridge console] ${message.message()}"
            when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> CerbosLog.error(text)
                ConsoleMessage.MessageLevel.WARNING -> CerbosLog.warn(text)
                else -> CerbosLog.debug(text)
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }
}
