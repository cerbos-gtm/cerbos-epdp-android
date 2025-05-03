package dev.cerbos.epdp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.View // Changed from GONE to View.GONE for clarity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue // Specific import
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap // More robust for potential concurrency
import java.util.concurrent.CopyOnWriteArrayList // Thread-safe list for queue
import java.util.concurrent.atomic.AtomicBoolean // Good for thread-safe flags
import java.util.concurrent.atomic.AtomicInteger


/**
 * CerbosEmbeddedPDP provides an Android View component to interact with a Cerbos Embedded Policy Decision Point (ePDP)
 * running within a hidden WebView. It handles loading the ePDP, managing communication (requests/responses),
 * and exposing a simplified API for authorization checks (`checkResources`).
 *
 * Usage:
 * 1. Add `CerbosEmbeddedPDP` to your layout XML or create it programmatically.
 * 2. Call `loadEmbeddedPDP(url)` with the URL of your Cerbos policy bundle (`.wasm` or `.js`).
 * 3. Set an optional listener using `setOnReadyListener` to know when the ePDP is initialized.
 * 4. Use `checkResources(request, callback)` to perform authorization checks.
 * 5. Set an optional listener using `onDecision` to receive raw decision logs from the ePDP.
 *
 * Note: This component uses a hidden WebView (`1x1` pixel, `GONE` visibility) to run the JavaScript-based ePDP.
 * All communication with the WebView happens securely via a JavascriptInterface and `evaluateJavascript`.
 * Callbacks and internal state updates are marshalled to the main Android UI thread.
 */
class CerbosEmbeddedPDP @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "CerbosPDP"
        private const val JS_BRIDGE_NAME = "AndroidBridge" // Name for the JS interface
        private const val BATCH_DELAY_MS = 10L // Delay for batching JS calls
        private const val JS_CALL_TIMEOUT_MS = 5000L // Timeout for waiting for a JS response

        // Inlined HTML content
        private const val EMBEDDED_HTML_CONTENT = """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0"> <!-- Added viewport for potential future debugging -->
            </head>
            <body>
            <script type="module">
                // Ensure console logs from the module are visible in Android Logcat
                // Note: Real device behavior might vary. Use WebView debugging if needed.
                console.log = (message) => { AndroidBridge.log("JS LOG: " + message); };
                console.error = (message) => { AndroidBridge.log("JS ERROR: " + message); };

                import { Embedded, AutoUpdatingLoader } from "https://esm.run/@cerbos/embedded@0.11";

                // Optional: Get a reference to the output div for status messages (visible during debugging)
                // const output = document.getElementById("cerbos");
                // output.innerHTML = "Cerbos SDK script loaded. Initializing bridge..."; // Update status

                async function loadCerbos(url) {
                    console.log("JS: loadCerbos called with URL:", url); // Use console.log which might be bridged
                    try {
                        window.cerbos = new Embedded(
                          new AutoUpdatingLoader(url, {
                            onLoad: (metadata) => {
                                console.log("JS: Cerbos PDP Loaded. Metadata: " + JSON.stringify(metadata));
                                // output.innerHTML = "Cerbos PDP Instance Ready."; // Update status
                                AndroidBridge.pdpReady(); // Signal native code
                            },
                            onError: (error) => { // Add error handling for loader
                                console.error("JS: Error loading Cerbos PDP: "+ error);
                                // output.innerHTML = "Error loading PDP: " + error;
                                // Optionally signal error back to native code if needed
                                AndroidBridge.pdpError(error.toString());
                            }
                            // Optional: Uncomment onDecision if needed, but ensure postDecisionLog exists in JsBridge
                            // onDecision: (log) => {
                               // AndroidBridge.postDecisionLog(JSON.stringify(log));
                            // }
                          })
                        );
                         console.log("JS: Embedded instance created.");
                    } catch (err) {
                         console.error("JS: Failed to instantiate Embedded: " + err);
                         // output.innerHTML = "Error instantiating Embedded: " + err;
                         // Optionally signal error back
                         AndroidBridge.pdpError(err.toString());
                    }
                }

                // Assign the load function to the window object so it's callable from native code
                window.loadCerbos = loadCerbos;
                console.log("JS: loadCerbos function attached to window.");

                // Signal that the basic SDK script is loaded and the bridge is ready
                // output.innerHTML = "Bridge initialized. Signaling SDK ready."; // Update status
                AndroidBridge.sdkReady();
                console.log("JS: Signaled sdkReady to native.");

            </script>
            <!-- Optional div for status messages, useful for debugging with WebView inspection -->
            <!-- <div id="cerbos" style="font-family: sans-serif; padding: 10px;">Initializing Cerbos Loader...</div> -->
            </body>
            </html>
        """

        // Setup Jackson ObjectMapper globally and configure it.
        // Registering the Kotlin module is essential for data class serialization/deserialization.
        // Setting NON_NULL inclusion avoids sending null fields in JSON payloads.
        private val mapper = jacksonObjectMapper().apply {
            registerKotlinModule()
            setSerializationInclusion(Include.NON_NULL)
        }
    }

    // --- Private Properties ---

    private val webView: WebView = WebView(context)

    // State flags indicating readiness of the underlying components
    private val isPDPLoaded = AtomicBoolean(false) // Is the policy bundle loaded and Cerbos instance ready?
    private val isSDKReady = AtomicBoolean(false)  // Is the core JS SDK (in HTML) ready to load the PDP?

    // Counter for generating unique IDs for JS calls to correlate callbacks
    private val jsCallIdCounter = AtomicInteger(0)

    // Map to store pending JS call callbacks, keyed by their unique ID
    // Using ConcurrentHashMap for thread safety, although access is mainly synchronized via Handlers
    private val pendingCallbacks = ConcurrentHashMap<String, JsCallback>()

    // Queue for JS calls made before the PDP is fully ready
    // Using CopyOnWriteArrayList for thread safety as calls might be enqueued from different threads
    // before being processed on the main thread.
    private val preReadyCallQueue = CopyOnWriteArrayList<Pair<String, JsCallback>>()

    // Queue for JS calls that are ready to be batched and sent to the WebView.
    // Accessed only via main thread handlers.
    private val batchQueue = mutableListOf<PendingJsCall>()

    // Listener to be invoked once the JS SDK signals readiness. Used by loadEmbeddedPDP.
    // Accessed only via main thread handlers.
    private var sdkReadyListener: ReadyListener? = null

    // --- Handlers for Thread Management ---
    // Ensures WebView operations and callbacks occur on the main UI thread.
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    // Handler dedicated to managing the batching of JS calls. Runs on the main thread.
    private val batchHandler = Handler(Looper.getMainLooper())

    // Runnable for executing the batch flush operation
    private val batchRunnable = Runnable { flushBatch() }

    // --- Public Callbacks ---

    /**
     * Optional listener invoked when the Cerbos ePDP instance within the WebView signals it's ready
     * to process `checkResources` calls. If set *after* the PDP is already ready, it will be invoked immediately.
     */
    var onPDPReadyListener: (() -> Unit)? = null
        set(listener) {
            field = listener
            // If the PDP is already ready when the listener is set, invoke it immediately on the main thread.
            if (isPDPLoaded.get()) {
                mainThreadHandler.post {
                    Log.d(TAG, "PDP already ready. Invoking onPDPReadyListener immediately.")
                    field?.invoke()
                }
            }
        }

    /**
     * Optional listener invoked for every policy decision evaluated by the ePDP.
     * Provides the raw decision log string from the underlying Cerbos engine.
     * Useful for auditing or debugging purposes. Requires `postDecisionLog` to be enabled
     * in the embedded HTML and implemented in the `JsBridge`.
     */
    var onDecision: ((log: String) -> Unit)? = null


    // --- Initialization ---

    init {
        setupWebView()
        // Load the base HTML content directly into the WebView.
        Log.d(TAG, "Loading embedded HTML content into WebView.")
        // Use loadDataWithBaseURL for loading from a string.
        // Base URL is set to null as the HTML doesn't rely on relative paths.
        webView.loadDataWithBaseURL(null, EMBEDDED_HTML_CONTENT, "text/html", "UTF-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Configure WebView for background JS execution: make it invisible and tiny.
        visibility = View.GONE // Use View.GONE for clarity over integer constant
        webView.layoutParams = LayoutParams(1, 1) // Minimal size

        // Crucial: Enable JavaScript execution.
        webView.settings.javaScriptEnabled = true
        // Optional: Enable DOM Storage if Cerbos library requires it (usually does for WASM caching)
        webView.settings.domStorageEnabled = true
        // Optional: Enable debugging if needed (requires debuggable build).
        // if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        //     WebView.setWebContentsDebuggingEnabled(true)
        // }

        // WebViewClient to monitor page loading events.
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Note: url will be null or about:blank when using loadDataWithBaseURL
                Log.d(TAG, "WebView finished loading internal HTML content (URL: $url)")
                // SDK readiness is signaled via JS bridge ('sdkReady'), not just page load.
            }

            // Optional: Add error handling for page loading
            // override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            //     super.onReceivedError(view, request, error)
            //     // Might not be triggered effectively for errors within loadDataWithBaseURL content
            //     Log.e(TAG, "WebView error loading ${request?.url}: ${error?.description}")
            // }
        }

        // Add the JavascriptInterface to allow JS code to call back into Kotlin/Java.
        webView.addJavascriptInterface(JsBridge(), JS_BRIDGE_NAME)

        // Add the configured WebView to this FrameLayout.
        addView(webView)
        Log.d(TAG, "WebView initialized and added to layout.")
    }

    // --- Public API Methods ---

    /**
     * Initiates loading of the Cerbos Embedded PDP from the specified URL.
     * This URL typically points to a `.wasm` or `.js` policy bundle.
     * The loading starts only after the base HTML and JS SDK are ready.
     *
     * @param url The URL or path to the Cerbos policy bundle.
     */
    fun loadEmbeddedPDP(url: String) {
        Log.d(TAG, "Request received to load Cerbos ePDP from: $url")
        // Define what happens once the SDK confirms it's ready
        val loadAction = Runnable { // Encapsulate in Runnable for posting
            Log.d(TAG, "SDK is ready. Instructing WebView to load Cerbos PDP from: $url")
            // Escape the URL properly for injection into a JS string literal
            val escapedUrl = url.replace("'", "\\'")
            // Use evaluateJavascript for potentially large URLs and better performance
            webView.evaluateJavascript("window.loadCerbos('$escapedUrl')") {
                // Optional: Log if the JS call itself failed, though success/failure of loading
                // is handled by the 'pdpReady' signal from JS.
                Log.d(TAG, "loadCerbos JS call executed.")
            }
        }

        // Actions must be performed on the main thread to safely access sdkReadyListener
        mainThreadHandler.post {
            // If SDK is already ready, execute immediately. Otherwise, set listener.
            if (isSDKReady.get()) {
                loadAction.run()
            } else {
                // Set up a one-time listener for SDK readiness
                sdkReadyListener = object : ReadyListener {
                    override fun onReady() {
                        // loadAction is already designed to run on the main thread,
                        // but post it again for safety ensures execution order if needed.
                        mainThreadHandler.post(loadAction)
                    }
                }
                Log.d(TAG, "SDK not ready yet. Queued PDP load action via listener.")
            }
        }
    }

    fun destroy() {
        Log.d(TAG, "Destroying CerbosEmbeddedPDP and its WebView.")
        // Stop any pending handlers/runnables
        mainThreadHandler.removeCallbacksAndMessages(null)
        batchHandler.removeCallbacksAndMessages(null)
        // Clean up WebView
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        webView.stopLoading()
        webView.loadUrl("about:blank") // Clear content
        webView.onPause() // Pause JS execution, etc.
        webView.removeAllViews()
        webView.destroy() // Crucial step
        // Clear collections (optional, but good practice)
        pendingCallbacks.clear()
        preReadyCallQueue.clear()
        batchQueue.clear()
    }

    /**
     * Performs an authorization check against the loaded Cerbos ePDP.
     *
     * @param request The `CheckResourcesRequest` containing principal, resources, and actions.
     * @param callback A lambda function that will be invoked asynchronously with the `CheckResourcesResponse`.
     *                 The callback will run on the main Android UI thread.
     */
    fun checkResources(request: CheckResourcesRequest, callback: (CheckResourcesResponse) -> Unit) {
        Log.d(TAG, "Received checkResources request.")

        // Ensure a unique requestId for correlation, if not provided by the caller.
        if (request.requestId == null) {
            request.requestId = UUID.randomUUID().toString()
            Log.d(TAG,"Generated requestId: ${request.requestId}")
        }

        try {
            // Serialize the request object to a JSON string.
            // Ensure proper JSON formatting, especially escaping within the string.
            val jsonRequest = mapper.writeValueAsString(request)
            val encodedRequest = Base64.encodeToString(jsonRequest.toByteArray(), Base64.NO_WRAP)
            val jsCode = "window.cerbos.checkResources(JSON.parse(window.atob('$encodedRequest')))"

            Log.v(TAG, "Serialized checkResources request : $jsCode") // Verbose log

            // Enqueue the JavaScript call to the Cerbos checkResources function.
            enqueueJavascriptCall(jsCode) { jsonResponse ->
                // This block is executed when the JS call completes (or times out)
                Log.v(TAG, "Received raw response for requestId ${request.requestId}: $jsonResponse") // Verbose log for raw response
                try {
                    // Deserialize the JSON response string into the response object.
                    val response = mapper.readValue<CheckResourcesResponse>(jsonResponse)
                    Log.d(TAG, "Deserialized checkResources response successfully for requestId: ${request.requestId}.")
                    // Invoke the original callback provided by the caller.
                    callback(response)
                } catch (e: JsonProcessingException) {
                    Log.e(TAG, "Failed to deserialize CheckResourcesResponse JSON for requestId ${request.requestId}: $jsonResponse", e)
                    // Handle error: maybe invoke callback with an error state or throw exception?
                    // Example: callback(CheckResourcesResponse(requestId = request.requestId!!, results = emptyList(), error = "Deserialization failed: ${e.message}"))
                } catch (e: Exception) { // Catch other potential errors during deserialization
                    Log.e(TAG, "Unexpected error processing response for requestId ${request.requestId}: $jsonResponse", e)
                    // Example: callback(CheckResourcesResponse(requestId = request.requestId!!, results = emptyList(), error = "Unexpected error: ${e.message}"))
                }
            }
        } catch (e: JsonProcessingException) {
            Log.e(TAG, "Failed to serialize CheckResourcesRequest", e)
            // Handle error: Cannot proceed if serialization fails. Maybe invoke callback with error?
            // Example: callback(CheckResourcesResponse(requestId = request.requestId ?: "unknown", results = emptyList(), error = "Serialization failed: ${e.message}"))
        }
    }

    // --- Private Helper Methods ---

    /**
     * Enqueues a JavaScript code snippet to be executed in the WebView.
     * If the PDP is ready, it adds the call to the batch queue for later execution.
     * If the PDP is not ready, it queues the call in a separate pre-ready queue.
     *
     * @param jsCode The JavaScript code to execute (expected to return a Promise).
     * @param callback The function to call with the JSON result string from the JS execution.
     */
    private fun enqueueJavascriptCall(jsCode: String, callback: (String) -> Unit) {
        // If PDP isn't loaded yet, add to the pre-ready queue and return.
        if (!isPDPLoaded.get()) {
            Log.d(TAG, "PDP not ready. Queuing call (pre-batch).")
            preReadyCallQueue.add(jsCode to JsCallback(callback))
            return
        }

        // PDP is ready, proceed with batching mechanism.
        val callId = "cb_${jsCallIdCounter.getAndIncrement()}"
        val jsCallback = JsCallback(callback, callId)

        val timeoutRunnable = Runnable {
            Log.w(TAG, "JS call timed out for ID: $callId")
            pendingCallbacks.remove(callId)?.let {
                Log.d(TAG,"Invoking timeout callback for ID: $callId")
                mainThreadHandler.post {
                    // Provide a JSON error object on timeout
                    it.callback.invoke("""{"error":"Timeout after ${JS_CALL_TIMEOUT_MS}ms", "cerbosCallId": null, "requestId": null, "results": []}""")
                }
            }
        }
        jsCallback.timeoutRunnable = timeoutRunnable

        pendingCallbacks[callId] = jsCallback
        batchHandler.postDelayed(timeoutRunnable, JS_CALL_TIMEOUT_MS)
        Log.d(TAG, "Queued for batching [$callId]")

        // Add to batch queue and schedule flush (on main thread)
        mainThreadHandler.post {
            batchQueue.add(PendingJsCall(callId, jsCode))
            batchHandler.removeCallbacks(batchRunnable)
            batchHandler.postDelayed(batchRunnable, BATCH_DELAY_MS)
        }
    }

    /**
     * Processes all JavaScript calls that were queued before the PDP was ready.
     */
    private fun flushPreReadyCallQueue() {
        mainThreadHandler.post {
            if (preReadyCallQueue.isNotEmpty()) {
                Log.d(TAG, "Flushing ${preReadyCallQueue.size} pre-ready JS calls.")
                val callsToProcess = preReadyCallQueue.toList()
                preReadyCallQueue.clear()
                callsToProcess.forEach { (js, cb) -> enqueueJavascriptCall(js, cb.callback) }
                Log.d(TAG, "Pre-ready queue flushed.")
            } else {
                Log.d(TAG, "Pre-ready queue is empty, nothing to flush.")
            }
        }
    }

    /**
     * Executes all pending JavaScript calls in the current batch queue (`batchQueue`).
     */
    private fun flushBatch() {
        // Must run on main thread
        if (batchQueue.isEmpty()) {
            Log.v(TAG, "Batch queue empty, nothing to flush.")
            return
        }

        val callsToFlush = ArrayList(batchQueue)
        batchQueue.clear()
        Log.d(TAG, "Flushing ${callsToFlush.size} JS calls as a batch.")

        val jsPayload = callsToFlush.joinToString(separator = "\n") { call ->
            // IIFE wrapper for each call
            """
            (function() {
                const callId = "${call.id}";
                try {
                    // The jsCode now likely contains the call (e.g., window.cerbos.checkResources(...))
                    // Ensure it returns a Promise or a value directly. Assuming Promise here.
                    Promise.resolve().then(() => ${call.jsCode}) // Wrap execution in a promise chain start
                        .then(function(result) {
                            // Handle undefined results explicitly as null for JSON compatibility
                            const resultJson = JSON.stringify(result === undefined ? null : result);
                            window.$JS_BRIDGE_NAME.postResponse(callId, resultJson);
                        })
                        .catch(function(error) {
                             // Attempt to stringify error, provide fallback
                             let errorJson;
                             try {
                                errorJson = JSON.stringify({ error: error.toString(), stack: error.stack });
                             } catch (stringifyError) {
                                errorJson = JSON.stringify({ error: "Failed to stringify JS error" });
                             }
                             console.error('JS Error for call ' + callId + ':', errorJson);
                             window.$JS_BRIDGE_NAME.postResponse(callId, errorJson);
                        });
                } catch(e) {
                     // Catch synchronous errors during setup/parsing within jsCode IIFE
                     let syncErrorJson;
                     try {
                        syncErrorJson = JSON.stringify({ error: e.toString(), stack: e.stack });
                     } catch (stringifyError) {
                        syncErrorJson = JSON.stringify({ error: "Failed to stringify sync JS error" });
                     }
                     console.error('Sync JS Error for call ' + callId + ':', syncErrorJson);
                     window.$JS_BRIDGE_NAME.postResponse(callId, syncErrorJson);
                }
            })();
            """.trimIndent()
        }

        // Log.v(TAG, "Executing batched JS payload:\n$jsPayload")
        webView.evaluateJavascript(jsPayload) {
            Log.v(TAG, "Batched JS payload evaluation initiated.")
        }
    }

    // --- Private Inner Classes ---

    private data class JsCallback(
        val callback: (String) -> Unit,
        val callId: String? = null,
        var timeoutRunnable: Runnable? = null
    )

    private data class PendingJsCall(
        val id: String,
        val jsCode: String,
    )

    private fun interface ReadyListener {
        fun onReady()
    }


    /**
     * Provides the methods callable from JavaScript within the WebView.
     */
    private inner class JsBridge {

        @JavascriptInterface
        fun postResponse(id: String, resultJson: String) {
            Log.v(TAG, "JS response received for ID: $id -> $resultJson")

            val jsCallback = pendingCallbacks.remove(id)
            if (jsCallback != null) {
                jsCallback.timeoutRunnable?.let { batchHandler.removeCallbacks(it) }
                mainThreadHandler.post {
                    try {
                        jsCallback.callback(resultJson)
                        Log.d(TAG, "Successfully processed response for ID: $id")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing callback for JS response ID $id", e)
                    }
                }
            } else {
                Log.w(TAG, "Received JS response for unknown or already timed-out ID: $id")
            }
        }

        @JavascriptInterface
        fun sdkReady() {
            if (isSDKReady.compareAndSet(false, true)) {
                Log.i(TAG, "JavaScript SDK reported ready.")
                mainThreadHandler.post {
                    sdkReadyListener?.onReady()
                    sdkReadyListener = null // Clear listener after use
                }
            } else {
                Log.d(TAG, "Duplicate sdkReady signal received, ignoring.")
            }
        }

        @JavascriptInterface
        fun pdpReady() {
            if (isPDPLoaded.compareAndSet(false, true)) {
                Log.i(TAG, "JavaScript ePDP reported ready.")
                mainThreadHandler.post {
                    onPDPReadyListener?.invoke()
                    // Flush queue needs to run *after* listener potentially adds new items
                    flushPreReadyCallQueue()
                }
            } else {
                Log.d(TAG, "Duplicate pdpReady signal received, ignoring.")
            }
        }

        /**
         * Called by JavaScript to pass through decision logs from the Cerbos engine.
         * Requires uncommenting `onDecision` in the HTML and adding this call there.
         *
         * @param logJson The JSON string representing a single decision log entry.
         */
        @JavascriptInterface
        fun postDecisionLog(logJson: String) {
            Log.v(TAG, "Received decision log from JS: $logJson")
            mainThreadHandler.post {
                onDecision?.invoke(logJson)
            }
        }

        // Optional: Add a simple logging bridge if console.log bridging is needed for debugging
         @JavascriptInterface
         fun log(message: String) {
             Log.d("$TAG/JS", message) // Log JS messages with a specific tag
         }

        // Optional: Bridge for PDP loading errors
         @JavascriptInterface
         fun pdpError(errorMessage: String) {
              Log.e(TAG, "JavaScript ePDP Loading Error: $errorMessage")
              // Potentially notify a listener or update state
         }
    }


    // --- Public Data Classes (API Contract) ---
    // (Keep all data classes as they were before)
    data class CheckResourcesRequest(
        var requestId: String? = null,
        val principal: Principal,
        val resources: List<ResourceAction>,
        val includeMeta: Boolean? = null,
        val auxData: AuxData? = null
    )
    data class Principal(
        val id: String,
        val roles: List<String>,
        val policyVersion: String? = null,
        val scope: String? = null,
        val attr: Map<String, Any?> = emptyMap(),
    )
    data class ResourceAction(
        val resource: Resource,
        val actions: List<String>,
    )
    data class Resource(
        val id: String,
        val kind: String,
        val policyVersion: String? = null,
        val scope: String? = null,
        val attr: Map<String, Any?> = emptyMap(),
    )
    data class AuxData(
        val jwt: Map<String, Any?>? = null
    )
    data class CheckResourcesResponse(
        val requestId: String?, // Make nullable to handle potential timeout/error responses
        val results: List<ResultEntry>,
        val error: String? = null,
        val cerbosCallId: String? = null,
    )
    data class ResultEntry(
        val resource: ResourceIdentifier,
        val actions: Map<String, Effect>,
        val validationErrors: List<ValidationError>? = null,
        val meta: Meta? = null,
        val outputs: List<OutputEntry>? = null,
    )
    data class ResourceIdentifier(
        val id: String,
        val kind: String,
        val policyVersion: String? = null,
        val scope: String? = null
    )
    data class ValidationError(
        val path: String,
        val message: String,
        val source: String
    )
    data class Meta(
        val actions: Map<String, ActionMeta>? = null,
        val effectiveDerivedRoles: List<String>? = null
    )
    data class ActionMeta(
        val matchedPolicy: String? = null,
        val matchedEffect: Effect? = null
    )
    data class OutputEntry(
        val source: String,
        val value: Any?
    )
    enum class Effect {
        EFFECT_ALLOW, EFFECT_DENY, EFFECT_UNSPECIFIED
    }
}