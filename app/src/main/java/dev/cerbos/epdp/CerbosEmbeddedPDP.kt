package dev.cerbos.epdp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
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
        private const val DEFAULT_WEBVIEW_URL = "file:///android_asset/cerbos_epdp.html" // Entry point HTML

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
     * Useful for auditing or debugging purposes.
     */
    var onDecision: ((log: String) -> Unit)? = null


    // --- Initialization ---

    init {
        setupWebView()
        // Load the base HTML file that contains the Cerbos JS SDK loader.
        Log.d(TAG, "Loading base WebView content from: $DEFAULT_WEBVIEW_URL")
        webView.loadUrl(DEFAULT_WEBVIEW_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Configure WebView for background JS execution: make it invisible and tiny.
        visibility = View.GONE // Use View.GONE for clarity over integer constant
        webView.layoutParams = LayoutParams(1, 1) // Minimal size

        // Crucial: Enable JavaScript execution.
        webView.settings.javaScriptEnabled = true
        // Optional: Enable debugging if needed (requires debuggable build).
        // if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        //     WebView.setWebContentsDebuggingEnabled(true)
        // }

        // WebViewClient to monitor page loading events.
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView page finished loading: $url")
                // Note: SDK readiness is signaled via JS bridge, not just page load.
            }

            // Optional: Add error handling for page loading
            // override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            //     super.onReceivedError(view, request, error)
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
            // Use evaluateJavascript for potentially large URLs and better performance
            webView.evaluateJavascript("window.loadCerbos('$url')") {
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
            val jsonRequest = mapper.writeValueAsString(request)
            Log.v(TAG, "Serialized checkResources request: $jsonRequest") // Verbose log for payload

            // Enqueue the JavaScript call to the Cerbos checkResources function.
            enqueueJavascriptCall("window.cerbos.checkResources($jsonRequest)") { jsonResponse ->
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
                    // For now, just logging. Consider adding error propagation to the callback.
                    // Example: callback(CheckResourcesResponse(requestId = request.requestId!!, results = emptyList(), error = e.message ?: "Deserialization failed"))
                } catch (e: Exception) { // Catch other potential errors during deserialization
                    Log.e(TAG, "Unexpected error processing response for requestId ${request.requestId}: $jsonResponse", e)
                    // Similar error handling consideration as above.
                    // Example: callback(CheckResourcesResponse(requestId = request.requestId!!, results = emptyList(), error = e.message ?: "Unexpected error"))
                }
            }
        } catch (e: JsonProcessingException) {
            Log.e(TAG, "Failed to serialize CheckResourcesRequest", e)
            // Handle error: Cannot proceed if serialization fails. Maybe invoke callback with error?
            // Example: callback(CheckResourcesResponse(requestId = request.requestId ?: "unknown", results = emptyList(), error = e.message ?: "Serialization failed"))
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
        // This check and addition can happen from any thread.
        if (!isPDPLoaded.get()) {
            Log.d(TAG, "PDP not ready. Queuing call (pre-batch): $jsCode")
            preReadyCallQueue.add(jsCode to JsCallback(callback))
            return
        }

        // PDP is ready, proceed with batching mechanism.
        // Generate unique ID and create callback wrapper.
        val callId = "cb_${jsCallIdCounter.getAndIncrement()}"
        val jsCallback = JsCallback(callback, callId)

        // Create a timeout runnable for this specific call.
        val timeoutRunnable = Runnable {
            Log.w(TAG, "JS call timed out for ID: $callId")
            // Attempt to remove the callback from the map. If successful, invoke it with a timeout error.
            pendingCallbacks.remove(callId)?.let {
                Log.d(TAG,"Invoking timeout callback for ID: $callId")
                // Ensure callback runs on main thread
                mainThreadHandler.post {
                    it.callback.invoke("""{"error":"Timeout after ${JS_CALL_TIMEOUT_MS}ms"}""")
                }
            }
        }
        jsCallback.timeoutRunnable = timeoutRunnable // Store runnable to allow cancellation

        // Store the callback, keyed by its ID. Can happen from any thread.
        pendingCallbacks[callId] = jsCallback

        // Schedule the timeout. Can happen from any thread.
        batchHandler.postDelayed(timeoutRunnable, JS_CALL_TIMEOUT_MS)

        Log.d(TAG, "Queued for batching [$callId]: $jsCode")

        // Add the call details to the batch queue and schedule the flush.
        // These actions MUST happen on the main thread to synchronize with flushBatch.
        mainThreadHandler.post {
            // Add call details needed by flushBatch to the member queue.
            batchQueue.add(PendingJsCall(callId, jsCode))

            // (Re)schedule the batch flush operation. This effectively debounces the flushes.
            batchHandler.removeCallbacks(batchRunnable) // Remove any previously scheduled flush
            batchHandler.postDelayed(batchRunnable, BATCH_DELAY_MS) // Schedule a new flush
        }
    }

    /**
     * Processes all JavaScript calls that were queued before the PDP was ready.
     * This is typically called once the PDP signals readiness via the JS Bridge.
     * Ensures execution happens on the main thread.
     */
    private fun flushPreReadyCallQueue() {
        // Run on main thread as it interacts with enqueueJavascriptCall's batching logic
        mainThreadHandler.post {
            if (preReadyCallQueue.isNotEmpty()) {
                Log.d(TAG, "Flushing ${preReadyCallQueue.size} pre-ready JS calls.")
                // Drain the queue and enqueue each call properly now that PDP is ready.
                // Create a local copy to avoid issues if new items are added concurrently (though unlikely here).
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
     * Constructs a single JavaScript payload containing all calls, wrapped in safety closures (IIFE).
     * This is triggered by the `batchRunnable` after a short delay and runs on the main thread.
     */
    private fun flushBatch() {
        // This method MUST run on the main thread because it accesses batchQueue
        // and calls webView.evaluateJavascript. It's invoked by batchHandler
        // which posts to the main looper, so this is guaranteed.

        if (batchQueue.isEmpty()) {
            Log.v(TAG, "Batch queue empty, nothing to flush.")
            return // Nothing to do
        }

        // Create a copy of the current batch and clear the main queue immediately
        // to allow new calls to be added while this batch is processed.
        val callsToFlush = ArrayList(batchQueue)
        batchQueue.clear()

        Log.d(TAG, "Flushing ${callsToFlush.size} JS calls as a batch.")

        // Construct the combined JavaScript payload.
        // Each call is wrapped in an Immediately Invoked Function Expression (IIFE)
        // to handle potential errors and ensure results/errors are posted back correctly via the bridge.
        val jsPayload = callsToFlush.joinToString(separator = "\n") { call ->
            """
            (function() {
                const callId = "${call.id}";
                try {
                    // Assuming the JS function returns a Promise
                    Promise.resolve(${call.jsCode})
                        .then(function(result) {
                            // Stringify result and send back via bridge
                            window.$JS_BRIDGE_NAME.postResponse(callId, JSON.stringify(result === undefined ? null : result));
                        })
                        .catch(function(error) {
                            // Stringify error and send back via bridge
                            console.error('JS Error for call ' + callId + ':', error); // Log JS error
                            window.$JS_BRIDGE_NAME.postResponse(callId, JSON.stringify({error: error.toString()}));
                        });
                } catch(e) {
                    // Catch synchronous errors during initial call setup
                    console.error('Sync JS Error for call ' + callId + ':', e); // Log JS error
                    window.$JS_BRIDGE_NAME.postResponse(callId, JSON.stringify({error: e.toString()}));
                }
            })();
            """.trimIndent()
        }

        Log.v(TAG, "Executing batched JS payload:\n$jsPayload") // Verbose log for JS payload
        // Evaluate the combined JavaScript payload in the WebView.
        webView.evaluateJavascript(jsPayload) {
            // This callback indicates the JS execution *started*, not completed.
            // Completion is signaled via the AndroidBridge.postResponse calls.
            Log.v(TAG, "Batched JS payload evaluation initiated.")
        }
    }

    // --- Private Inner Classes ---

    /**
     * Represents a pending JavaScript call's callback and its associated timeout handler.
     */
    private data class JsCallback(
        val callback: (String) -> Unit,
        val callId: String? = null, // ID used when part of the main pendingCallbacks map
        var timeoutRunnable: Runnable? = null // Runnable for timeout cancellation
    )

    /**
     * Represents the details of a JS call waiting in the batch queue.
     */
    private data class PendingJsCall(
        val id: String,
        val jsCode: String,
    )

    /**
     * Interface defining a simple callback for readiness signals.
     */
    private fun interface ReadyListener { // Can be a functional interface
        fun onReady()
    }


    /**
     * Provides the methods callable from JavaScript within the WebView.
     * This acts as the bridge from the JS environment back to the Android app.
     * IMPORTANT: Methods annotated with `@JavascriptInterface` are exposed to JS.
     * All methods posting back to Android UI/state should use `mainThreadHandler`.
     */
    private inner class JsBridge {

        /**
         * Called by JavaScript to deliver the result (or error) of an asynchronous operation.
         *
         * @param id The unique ID of the JS call this response corresponds to.
         * @param resultJson The JSON string representation of the result or an error object.
         */
        @JavascriptInterface
        fun postResponse(id: String, resultJson: String) {
            Log.v(TAG, "JS response received for ID: $id -> $resultJson") // Verbose log for raw response

            // Retrieve the corresponding callback information using the ID.
            // This map access is thread-safe.
            val jsCallback = pendingCallbacks.remove(id)

            if (jsCallback != null) {
                // If a callback was found, cancel its timeout runnable.
                // This needs to happen via the handler that scheduled it.
                jsCallback.timeoutRunnable?.let { batchHandler.removeCallbacks(it) }

                // Execute the callback on the main thread with the received result.
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

        /**
         * Called by JavaScript when the core SDK (in the HTML file) is loaded and ready
         * to receive the command to load the actual Cerbos PDP bundle.
         */
        @JavascriptInterface
        fun sdkReady() {
            // Use atomic flag to ensure this logic runs only once.
            if (isSDKReady.compareAndSet(false, true)) {
                Log.i(TAG, "JavaScript SDK reported ready.")
                // Post to main thread to safely interact with the listener.
                mainThreadHandler.post {
                    // Notify the listener waiting for the SDK to be ready (if any).
                    sdkReadyListener?.onReady()
                    // Clear the listener after invoking it (assuming one-shot readiness).
                    sdkReadyListener = null
                }
            } else {
                Log.d(TAG, "Duplicate sdkReady signal received, ignoring.")
            }
        }

        /**
         * Called by JavaScript once the Cerbos ePDP instance has been successfully loaded
         * (e.g., policy bundle downloaded and compiled) and is ready to handle `checkResources` calls.
         */
        @JavascriptInterface
        fun pdpReady() {
            // Use atomic flag to ensure this logic runs only once.
            if (isPDPLoaded.compareAndSet(false, true)) {
                Log.i(TAG, "JavaScript ePDP reported ready.")
                // Post to main thread to safely invoke listeners and flush queue.
                mainThreadHandler.post {
                    // Invoke the public listener if it's set.
                    onPDPReadyListener?.invoke()
                    // Process any calls that were queued before the PDP was ready.
                    // flushPreReadyCallQueue already posts to the main thread internally.
                    flushPreReadyCallQueue()
                }
            } else {
                Log.d(TAG, "Duplicate pdpReady signal received, ignoring.")
            }
        }

        /**
         * Called by JavaScript to pass through decision logs from the Cerbos engine.
         *
         * @param logJson The JSON string representing a single decision log entry.
         */
        @JavascriptInterface
        fun postDecisionLog(logJson: String) {
            Log.v(TAG, "Received decision log from JS: $logJson") // Verbose log for decision
            // Forward the log to the public listener, ensuring it runs on the main thread.
            mainThreadHandler.post {
                onDecision?.invoke(logJson)
            }
        }
    }


    // --- Public Data Classes (API Contract) ---
    // These data classes define the structure for requests and responses
    // used in the public `checkResources` method. They mirror the expected
    // Cerbos API structures.

    /** Represents the request for a batch authorization check. */
    data class CheckResourcesRequest(
        /** Optional ID for correlating requests and responses, especially in logs. Auto-generated if null. */
        var requestId: String? = null,
        /** The principal (user or service) making the request. */
        val principal: Principal,
        /** A list of resource/action pairs to check authorization for. */
        val resources: List<ResourceAction>,
        /** Optional: Include details about the result (e.g., matched policies). Defaults to minimal. */
        val includeMeta: Boolean? = null,
        /** Optional: Auxiliary data to be used in policy conditions. */
        val auxData: AuxData? = null
    )

    /** Represents a principal (e.g., user, service) performing an action. */
    data class Principal(
        val id: String,
        val roles: List<String>,
        val policyVersion: String? = null, // Optional: Specific policy version for this principal
        val scope: String? = null,          // Optional: Scope for hierarchical policies
        val attr: Map<String, Any?> = emptyMap(), // Attributes of the principal (e.g., department, location)
    )

    /** Represents a resource and the actions being performed on it. */
    data class ResourceAction(
        val resource: Resource,
        val actions: List<String>,
    )

    /** Represents a resource being acted upon. */
    data class Resource(
        val id: String,
        val kind: String, // Type of resource (e.g., "document", "order")
        val policyVersion: String? = null, // Optional: Specific policy version for this resource
        val scope: String? = null,          // Optional: Scope for hierarchical policies
        val attr: Map<String, Any?> = emptyMap(), // Attributes of the resource (e.g., owner, status)
    )

    /** Represents auxiliary data provided with the check request. */
    data class AuxData(
        val jwt: Map<String, Any?>? = null // Example: Decoded JWT payload
        // Add other auxiliary data fields as needed
    )

    /** Represents the response containing authorization results for the batch check. */
    data class CheckResourcesResponse(
        /** ID correlating this response to the request. */
        val requestId: String,
        /** List of results, one for each ResourceAction in the request. */
        val results: List<ResultEntry>,
        /** Optional: Error message if the entire check failed */
        val error: String? = null,
        /** Optional: ID assigned by Cerbos for this specific call (useful for correlating logs). */
        val cerbosCallId: String? = null,
    )

    /** Represents the authorization result for a single resource and its actions. */
    data class ResultEntry(
        /** Identifies the resource this result pertains to (matches one from the request). */
        val resource: ResourceIdentifier,
        /** Map of action names to their resulting effect (ALLOW or DENY). */
        val actions: Map<String, Effect>,
        /** List of validation errors encountered during policy evaluation for this resource. */
        val validationErrors: List<ValidationError>? = null,
        /** Metadata about the evaluation (e.g., matched policies), if requested via `includeMeta`. */
        val meta: Meta? = null,
        /** Output values generated by policy rules (e.g., masked fields), if any. */
        val outputs: List<OutputEntry>? = null,
    )

    /** Simplified identifier for a resource within a response entry. */
    data class ResourceIdentifier(
        val id: String,
        val kind: String,
        val policyVersion: String? = null,
        val scope: String? = null
    )

    /** Represents a validation error from policy evaluation. */
    data class ValidationError(
        val path: String,
        val message: String,
        val source: String // e.g., "SOURCE_PRINCIPAL", "SOURCE_RESOURCE"
    )

    /** Represents metadata about the policy decision for a resource. */
    data class Meta(
        val actions: Map<String, ActionMeta>? = null,
        /** The effective derived roles considered during evaluation. */
        val effectiveDerivedRoles: List<String>? = null
    )

    /** Metadata specific to a single action's evaluation. */
    data class ActionMeta(
        /** The policy rule or principal policy rule that produced the result. */
        val matchedPolicy: String? = null,
        /** The specific effect produced by the matched policy (ALLOW/DENY). Might differ if overridden. */
        val matchedEffect: Effect? = null
    )

    /** Represents an output value produced by a policy rule. */
    data class OutputEntry(
        /** Identifies the source policy rule that generated this output. */
        val source: String, // e.g., "resource.leave_request.v1#approve"
        /** The actual value produced. */
        val value: Any? // Can be any valid JSON type
    )

    /** Represents the outcome of an authorization check for a specific action. */
    enum class Effect {
        EFFECT_ALLOW, EFFECT_DENY, EFFECT_UNSPECIFIED // Added Unspecified for completeness
    }
}