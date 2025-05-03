# Cerbos Embedded PDP for Android

This project provides an Android View component (`CerbosEmbeddedPDP`) that allows you to run a Cerbos Embedded Policy Decision Point (ePDP) directly within your Android application. It leverages the official `@cerbos/embedded` JavaScript/WASM library running inside a hidden WebView, enabling local, low-latency authorization decisions without requiring network calls to a separate Cerbos PDP instance for every check.

## Overview

The core idea is to embed the Cerbos decision-making logic into the client application itself. This is useful for scenarios where:

- Network latency for authorization checks is critical.
- Offline authorization capabilities are needed (once the policy bundle is loaded).
- Reducing server-side load is a goal.

The `CerbosEmbeddedPDP` component handles:

1.  Setting up a hidden WebView.
2.  Loading the Cerbos Embedded JS SDK via a simple HTML file (`cerbos_epdp.html`).
3.  Loading the Cerbos policy bundle (WASM or JS) from a specified URL.
4.  Providing a Kotlin/Java API (`checkResources`) to interact with the ePDP.
5.  Managing the asynchronous communication between the Android app and the JavaScript environment in the WebView.
6.  Handling readiness states and queueing calls made before the PDP is fully initialized.
7.  Serializing/deserializing request/response objects using Jackson.

## How it Works

1.  **Hidden WebView:** The `CerbosEmbeddedPDP` view extends `FrameLayout` and contains a `WebView` configured to be invisible (`1x1` pixel, `View.GONE`).
2.  **HTML/JS Loader (`cerbos_epdp.html`):** This minimal HTML file is loaded into the WebView first. It imports the `@cerbos/embedded` library from a CDN (`esm.run`) and sets up basic functions (`loadCerbos`) and the communication bridge.
3.  **JavaScript Bridge (`AndroidBridge`):** A native Kotlin object (`JsBridge` inner class) is exposed to the WebView's JavaScript environment under the name `AndroidBridge`. This allows JavaScript to call back into the Android application. Key methods exposed:
    - `sdkReady()`: Called by JS when the `@cerbos/embedded` SDK script itself is loaded and ready to initialize the Cerbos engine.
    - `pdpReady()`: Called by JS after the policy bundle (specified by the URL) has been successfully loaded and the Cerbos ePDP instance is ready to evaluate policies.
    - `postResponse(id, resultJson)`: Called by JS to send the result (or error) of an asynchronous operation (like `checkResources`) back to Android, correlated by a unique ID.
    - `postDecisionLog(logJson)`: Called by JS to forward raw decision audit logs from the Cerbos engine.
4.  **Loading the PDP:** The Android app calls `loadEmbeddedPDP(url)`. This function waits for the `sdkReady` signal (if not already received) and then instructs the WebView (via `evaluateJavascript`) to call `window.loadCerbos(url)`. The JS code then handles fetching and initializing the policy bundle. Once complete, it signals back via `pdpReady()`.
5.  **Authorization Checks (`checkResources`):**
    - The Android app calls `checkResources(request, callback)`.
    - The `request` object (Kotlin data class) is serialized to JSON using Jackson.
    - A unique call ID is generated.
    - The call is added to a batch queue.
    - A short delay (`BATCH_DELAY_MS`) is used to collect multiple calls into a single batch.
    - The `flushBatch` function constructs a JavaScript payload containing all calls in the batch, wrapping each in an IIFE (Immediately Invoked Function Expression) for safety.
    - This combined payload is executed in the WebView using `evaluateJavascript`.
    - The JS code executes `cerbos.checkResources(...)` for each call, which returns a Promise.
    - When the Promise resolves (or rejects), the JS code stringifies the result/error and sends it back via `AndroidBridge.postResponse(callId, jsonResult)`.
6.  **Callback Handling:** The `JsBridge.postResponse` method receives the result, finds the corresponding pending callback using the `callId`, deserializes the JSON result into the appropriate Kotlin data class (e.g., `CheckResourcesResponse`), and invokes the original callback provided by the Android app. Callbacks are always executed on the main Android UI thread.
7.  **Readiness Queueing:** If `checkResources` is called _before_ `pdpReady` is signaled, the call is placed in a `preReadyCallQueue`. Once `pdpReady` is signaled, this queue is flushed, and the calls are processed normally via the batching mechanism.
8.  **Threading:** All interactions with the WebView (`evaluateJavascript`) and all callbacks back into the Android application (listeners, `checkResources` callback) are marshalled onto the main Android UI thread using `Handler(Looper.getMainLooper())` to ensure thread safety.

## Features

- Runs Cerbos ePDP locally within an Android app.
- Uses the official `@cerbos/embedded` library.
- Provides a clean Kotlin API (`checkResources`) mirroring the standard Cerbos API structure.
- Handles asynchronous communication with the JS environment.
- Batches multiple `checkResources` calls for efficiency.
- Handles readiness states gracefully.
- Provides optional callbacks for PDP readiness (`onPDPReadyListener`) and decision logs (`onDecision`).
- Uses Jackson for reliable Kotlin data class serialization/deserialization.

## Getting Started / Integration

Follow these steps to add the `CerbosEmbeddedPDP` to your Android project:

1.  **Copy Files:**

    Copy the `CerbosEmbeddedPDP.kt` file into your project's source directory (e.g., `app/src/main/java/your/package/name/`). Make sure to update the `package` declaration at the top of the file if necessary.

2.  **Add Dependency:** Add the Jackson Kotlin module dependency to your app-level `build.gradle` (or `build.gradle.kts`) file:

    ```gradle
    // build.gradle (Groovy)
    dependencies {
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.+") // Use the latest 2.x version
        // ... other dependencies
    }

    // build.gradle.kts (Kotlin)
    dependencies {
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.+") // Use the latest 2.x version
        // ... other dependencies
    }
    ```

    Sync your Gradle project.

3.  **Add Internet Permission:** Since the component loads the Cerbos JS SDK from a CDN and potentially the policy bundle from a URL, you need the internet permission in your `AndroidManifest.xml`:

    ```xml
    <uses-permission android:name="android.permission.INTERNET" />

    <application ...>
        ...
    </application>
    ```

4.  **Add to Layout:** Add the `CerbosEmbeddedPDP` view to your XML layout file:

    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <androidx.constraintlayout.widget.ConstraintLayout
        xmlns:android="http://schemas.android.com/apk/res/android"
        xmlns:app="http://schemas.android.com/apk/res-auto"
        xmlns:tools="http://schemas.android.com/tools"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        tools:context=".MainActivity">

        <!-- Other UI elements -->
        <TextView
            android:id="@+id/statusTextView"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_margin="16dp"
            android:text="Initializing..."
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />

        <!-- The Cerbos Embedded PDP View (it's invisible) -->
        <dev.cerbos.epdp.CerbosEmbeddedPDP
            android:id="@+id/cerbosPDP"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toBottomOf="@id/statusTextView" />
            <!-- Position doesn't really matter as it's GONE, but needs to be in the layout -->

    </androidx.constraintlayout.widget.ConstraintLayout>
    ```

    _Remember to replace `dev.cerbos.epdp` with the actual package name where you placed `CerbosEmbeddedPDP.kt`._

5.  **Initialize and Use in Activity/Fragment:**
    - Get a reference to the view (using ViewBinding is recommended).
    - Set the `onPDPReadyListener` to know when you can start making checks.
    - Optionally set the `onDecision` listener for logging.
    - Call `loadEmbeddedPDP()` with the URL of your policy bundle.
    - Inside the `onPDPReadyListener`, construct your `CheckResourcesRequest` and call `checkResources()`.
    - Process the `CheckResourcesResponse` in the callback lambda.

## Usage Example (Kotlin)

See `MainActivity.kt` in this repository for a full example. Key snippets:

```kotlin
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import dev.cerbos.epdp.CerbosEmbeddedPDP // Use your actual package
import your.package.name.databinding.ActivityMainBinding // Use your ViewBinding class

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cerbosEmbeddedPDP: CerbosEmbeddedPDP

    // Replace with your actual policy bundle URL from Cerbos Hub
    private val CERBOS_BUNDLE_URL = "https://lite.cerbos.cloud/bundle?workspace=...."
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cerbosEmbeddedPDP = binding.cerbosPDP // Get view reference

        updateStatus("Initializing Cerbos PDP View...")

        // --- Set Listeners BEFORE Loading ---
        cerbosEmbeddedPDP.onPDPReadyListener = {
            Log.i(TAG, ">>> Cerbos PDP is READY! <<<")
            runOnUiThread { updateStatus("PDP Ready. Performing check...") }
            performAuthorizationCheck() // Now safe to make checks
        }

        cerbosEmbeddedPDP.onDecision = { decisionLog ->
            Log.v(TAG, "Decision Log: $decisionLog")
            // Optionally update UI or send logs elsewhere
        }

        // --- Start Loading ---
        Log.d(TAG, "Loading Cerbos PDP from: $CERBOS_BUNDLE_URL")
        cerbosEmbeddedPDP.loadEmbeddedPDP(CERBOS_BUNDLE_URL)
        updateStatus("Loading PDP from URL...")
    }

    private fun performAuthorizationCheck() {
        Log.d(TAG, "Preparing CheckResources request...")

        // Build the request using the data classes provided within CerbosEmbeddedPDP
        val request = CerbosEmbeddedPDP.CheckResourcesRequest(
            principal = CerbosEmbeddedPDP.Principal(
                id = "alice",
                roles = listOf("user", "employee"),
                attr = mapOf("department" to "engineering")
            ),
            resources = listOf(
                CerbosEmbeddedPDP.ResourceAction(
                    resource = CerbosEmbeddedPDP.Resource(
                        id = "resource123",
                        kind = "document",
                        attr = mapOf("owner" to "alice", "public" to false)
                    ),
                    actions = listOf("view", "edit", "delete")
                )
                // Add more ResourceActions as needed
            )
        )

        Log.d(TAG, "Calling checkResources...")
        cerbosEmbeddedPDP.checkResources(request) { response ->
            // --- Handle the Response (runs on main thread) ---
            Log.i(TAG, "<<< CheckResources Response Received (Request ID: ${response.requestId}) >>>")

            val allowed = mutableListOf<String>()
            val denied = mutableListOf<String>()

            response.results.forEach { resultEntry ->
                resultEntry.actions.forEach { (action, effect) ->
                    val resourceId = resultEntry.resource.id
                    if (effect == CerbosEmbeddedPDP.Effect.EFFECT_ALLOW) {
                        allowed.add("$action on $resourceId")
                        Log.i(TAG, "ALLOW: $action on resource $resourceId")
                    } else {
                        denied.add("$action on $resourceId")
                        Log.w(TAG, "DENY: $action on resource $resourceId")
                    }
                }
            }

            updateStatus("Check complete.\nAllowed: ${allowed.joinToString()}\nDenied: ${denied.joinToString()}")
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            Log.d(TAG, "Status Update: $message")
            binding.statusTextView.text = message
        }
    }
}
```

## API Reference

- **`fun loadEmbeddedPDP(url: String)`**: Starts the asynchronous loading of the Cerbos policy bundle from the given `url`. The `onPDPReadyListener` will be invoked upon completion.
- **`fun checkResources(request: CheckResourcesRequest, callback: (CheckResourcesResponse) -> Unit)`**: Performs a batch authorization check. The `request` object defines the principal, resources, and actions. The `callback` lambda is invoked asynchronously on the main thread with the `CheckResourcesResponse` containing the results.
- **`var onPDPReadyListener: (() -> Unit)?`**: A lambda function invoked on the main thread when the ePDP is fully loaded and ready to process `checkResources` calls.
- **`var onDecision: ((log: String) -> Unit)?`**: A lambda function invoked on the main thread for each raw decision log entry generated by the Cerbos engine. The parameter is the decision log as a JSON string.

## Data Structures

The component includes nested data classes mirroring the standard Cerbos API structure for requests and responses:

- `CheckResourcesRequest`
- `Principal`
- `ResourceAction`
- `Resource`
- `AuxData` (optional)
- `CheckResourcesResponse`
- `ResultEntry`
- `ResourceIdentifier`
- `Effect` (Enum: `EFFECT_ALLOW`, `EFFECT_DENY`, `EFFECT_UNSPECIFIED`)
- `ValidationError` (optional)
- `Meta` (optional)
- `ActionMeta` (optional)
- `OutputEntry` (optional)

Refer to the [Cerbos API documentation](https://docs.cerbos.dev/cerbos/latest/api/grpc) for details on the fields within these structures.

## Troubleshooting / Notes

- **Policy Bundle URL:** Ensure the URL provided to `loadEmbeddedPDP` is accessible from the Android device and points to a valid Cerbos policy bundle.
- **Initialization Time:** Loading the WASM bundle and initializing the engine can take a few seconds, especially on the first run or slower devices. Use the `onPDPReadyListener` to gate calls to `checkResources`.
- **Performance:** Once initialized, `checkResources` calls are evaluated locally and should be fast. Performance depends on device CPU and policy complexity. Batching helps reduce JS<->Native communication overhead.
- **WebView Security:** JavaScript is enabled for the hidden WebView. While the loaded HTML is minimal and the JS bridge interface is specific, be aware of the general security implications of running JavaScript.
- **Error Handling:** The current implementation primarily logs errors (serialization, deserialization, JS timeouts). Consider enhancing the `checkResources` callback to include an optional error parameter for more robust error handling in your application.
- **Memory Usage:** The WebView and the loaded WASM/JS bundle will consume memory. Monitor usage for your specific policies and device targets.
