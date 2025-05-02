package dev.cerbos.epdp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule


val mapper = jacksonObjectMapper().registerKotlinModule()

class CerbosEmbeddedPDP @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "CerbosPDP"
    }

    private val webView = WebView(context)
    private var autoInjectJs: String? = null
    private val idCounter = AtomicInteger(0)
    private var pdpIsReady = false
    private var onPDPReadyListener: (() -> Unit)? = null
    private var sdkIsReady = false
    private var onSDKReadyListener: (() -> Unit)? = null
    var onDecision: ((log: String) -> Unit)? = null

    private val callbackMap = mutableMapOf<String, (String) -> Unit>()
    private val callQueue = mutableListOf<Pair<String, (String) -> Unit>>()

    private val pendingBatch = mutableListOf<PendingJsCall>()
    private val batchHandler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val batchDelayMs = 10L
    private val batchRunnable = Runnable { flushBatch() }

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        visibility = GONE
        webView.layoutParams = LayoutParams(1, 1)
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished loading: $url")
                autoInjectJs?.let {
                    Log.d(TAG, "Injecting auto JS")
                    webView.evaluateJavascript(it, null)
                }
            }
        }

        addView(webView)

        webView.addJavascriptInterface(CerbosEmbeddedPDPInterface(
            dispatcher = { id, result ->
                Log.d(TAG, "JS response received for ID: $id -> $result")
                // Run on the main thread
                mainHandler.post {
                    val call = pendingBatch.find { it.id == id }
                    call?.timeoutRunnable?.let {
                        batchHandler.removeCallbacks(it)
                    }

                    pendingBatch.removeAll { it.id == id }
                    callbackMap.remove(id)?.invoke(result)
                }
            },
            pdpReadyHandler = {
                if (!pdpIsReady) {
                    pdpIsReady = true
                    Log.d(TAG, "Received ready signal from JS. Flushing pre-ready queue.")
                    // Run on the main thread
                    mainHandler.post {
                        flushCallQueue()
                        onPDPReadyListener?.invoke()
                    }
                }
            },
            sdkLoadedHandler = {
                if (!sdkIsReady) {
                    sdkIsReady = true
                    // Run on the main thread
                    mainHandler.post {
                        onSDKReadyListener?.invoke()
                    }
                }
            },
            onDecisionHandler = { log ->
                Log.d(TAG, "Received decision from JS: $log")
                // Run on the main thread
                mainHandler.post {
                    onDecision?.invoke(log)
                }
            }
        ), "AndroidBridge")

        webView.loadUrl("file:///android_asset/cerbos_epdp.html")
        Log.d(TAG, "WebView initialized")

    }

    fun loadEmbeddedPDP(url: String) {
        onSDKReadyListener = {
            webView.evaluateJavascript("window.loadCerbos('$url')") {}
        }
    }

    fun setOnReadyListener(listener: () -> Unit) {
        onPDPReadyListener = listener
        if (pdpIsReady) {
            Log.d(TAG, "Already ready. Invoking onReadyListener immediately.")
            listener()
        }
    }

    fun enqueueJavascriptCall(jsCode: String, callback: (String) -> Unit) {
        if (!pdpIsReady) {
            Log.d(TAG, "PDP not ready. Queuing call (pre-batch): $jsCode")
            callQueue.add(jsCode to callback)
            return
        }

        val id = "cb_${idCounter.getAndIncrement()}"
        val timeoutRunnable = Runnable {
            Log.w(TAG, "JS call timed out for ID: $id")
            // Run on the main thread
            mainHandler.post {
                callbackMap.remove(id)?.invoke("""{"error":"Timeout after 5 seconds"}""")
                pendingBatch.removeAll { it.id == id }
            }
        }

        val call = PendingJsCall(id, jsCode, callback, timeoutRunnable)
        callbackMap[id] = callback
        pendingBatch.add(call)

        Log.d(TAG, "Queued for batching [$id]: $jsCode")
        batchHandler.removeCallbacks(batchRunnable)
        batchHandler.postDelayed(batchRunnable, batchDelayMs)

        batchHandler.postDelayed(timeoutRunnable, 5000L) // 5s timeout
    }

    private fun flushCallQueue() {
        Log.d(TAG, "Flushing ${callQueue.size} pre-ready JS calls")
        callQueue.forEach { (js, cb) -> enqueueJavascriptCall(js, cb) }
        callQueue.clear()
    }

    private fun flushBatch() {
        // Ensure this runs on the main thread
        mainHandler.post{
            if (pendingBatch.isEmpty()) return@post

            val jsPayload = pendingBatch.joinToString("\n") { call ->
                """
            (function() {
                try {
                    Promise.resolve(${call.jsCode}).then(function(result) {
                        AndroidBridge.postResponse("${call.id}", JSON.stringify(result));
                    }).catch(function(error) {
                        AndroidBridge.postResponse("${call.id}", JSON.stringify({error: error.toString()}));
                    });
                } catch(e) {
                    AndroidBridge.postResponse("${call.id}", JSON.stringify({error: e.toString()}));
                }
            })();
            """.trimIndent()
            }

            Log.d(TAG, "Flushing ${pendingBatch.size} JS calls as batch")
            webView.evaluateJavascript(jsPayload, null)
        }
    }


    fun checkResources(request: CheckResourcesRequest) {
        Log.d(TAG, "Checking resources")
        if(request.requestId == null){
            request.requestId = UUID.randomUUID().toString()
        }
        val jsonString = mapper.writeValueAsString(request)

        enqueueJavascriptCall("window.cerbos.checkResources($jsonString)") {
            Log.d(TAG, "Resources check result: $it")
        }
    }

    private data class PendingJsCall(
        val id: String,
        val jsCode: String,
        val callback: (String) -> Unit,
        var timeoutRunnable: Runnable? = null
    )


    data class CheckResourcesRequest(
        var requestId: String? = null,
        val principal: Principal,
        val resources: List<Resource>,
    )


    data class Principal(
        val id: String,
        val policyVersion: String,
        val roles: List<String>,
        val attr: Map<String, Any?> = emptyMap(),
    )


    data class Resource(
        val resource: ResourceObject,
        val actions: List<String>,
    )


    data class ResourceObject(
        val id: String,
        val kind: String,
        val policyVersion: String,
        val attr: Map<String, Any?> = emptyMap(),
    )
}

