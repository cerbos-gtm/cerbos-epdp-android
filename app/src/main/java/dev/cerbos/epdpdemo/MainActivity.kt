package dev.cerbos.epdpdemo// <-- Make sure this matches your project's package

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import dev.cerbos.epdp.CerbosEmbeddedPDP
import dev.cerbos.epdpdemo.databinding.ActivityMainBinding



class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cerbosEmbeddedPDP: CerbosEmbeddedPDP

    // Define a consistent tag for logging
    companion object {
        private const val APP_TAG = "MainActivity"
        // Example policy bundle URL (Replace with your actual bundle if needed)
        private const val CERBOS_BUNDLE_URL = "https://lite.cerbos.cloud/bundle?workspace=7SRBU5GTJZKS&label=f481a2c9c90ee3ae4deae7b7f656d65d1cd608828f5853d21e9ca383d479223a"
    }

    @SuppressLint("SetJavaScriptEnabled") // CerbosEmbeddedPDP handles JS enabling internally
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- ViewBinding Setup ---
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Toolbar Setup (Optional) ---
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Cerbos PDP Demo"

        // --- Find the Cerbos PDP View ---
        // Use ViewBinding for type safety and conciseness
        cerbosEmbeddedPDP = binding.cerbosPDP
        Log.d(APP_TAG, "CerbosEmbeddedPDP view found.")
        updateStatus("Cerbos PDP View Initialized. Loading...")


        // --- Set Listeners BEFORE Loading ---

        // Listener for raw decision logs (optional)
        cerbosEmbeddedPDP.onDecision = { decisionLog ->
            // Log decision details (can be verbose)
            Log.v(APP_TAG, "Decision Log: $decisionLog")
            // Optionally update UI, but might be too frequent
            // runOnUiThread { binding.statusTextView.append("\nDecision logged...") }
        }
        Log.d(APP_TAG, "onDecision listener set.")

        // Listener for when the PDP engine is fully loaded and ready
        cerbosEmbeddedPDP.onPDPReadyListener = {
            Log.i(APP_TAG, ">>> Cerbos PDP is READY! <<<")
            runOnUiThread { updateStatus("PDP Ready. Performing check...") }

            // --- Perform CheckResources *AFTER* PDP is ready ---
            performAuthorizationCheck()
        }
        Log.d(APP_TAG, "onPDPReadyListener set.")


        // --- Start Loading the PDP ---
        // This initiates the asynchronous loading process (HTML -> SDK -> Bundle)
        Log.d(APP_TAG, "Calling loadEmbeddedPDP with URL: $CERBOS_BUNDLE_URL")
        cerbosEmbeddedPDP.loadEmbeddedPDP(CERBOS_BUNDLE_URL)
        updateStatus("Loading PDP from URL...")

    }

    private fun performAuthorizationCheck() {
        Log.d(APP_TAG, "Preparing CheckResources request...")

        // Create the request object using the data classes from CerbosEmbeddedPDP
        val request = CerbosEmbeddedPDP.CheckResourcesRequest(
            // requestId is optional, will be generated if null
            principal = CerbosEmbeddedPDP.Principal(
                id = "alice", // Example principal ID
                policyVersion = "default",
                roles = listOf("user", "employee"), // Example roles
                attr = mapOf("department" to "engineering", "region" to "emea") // Example attributes
            ),
            resources = listOf(
                CerbosEmbeddedPDP.ResourceAction(
                    resource = CerbosEmbeddedPDP.Resource(
                        id = "feature_flag_abc", // Example resource ID
                        kind = "feature_flag", // Example resource kind
                        policyVersion = "default",
                        attr = mapOf("beta_enabled" to true) // Example resource attributes
                    ),
                    actions = listOf("read", "enable", "disable") // Actions to check
                ),
                CerbosEmbeddedPDP.ResourceAction(
                    resource = CerbosEmbeddedPDP.Resource(
                        id = "document_xyz",
                        kind = "document",
                        policyVersion = "default",
                        attr = mapOf("owner" to "bob", "public" to false)
                    ),
                    actions = listOf("view", "edit") // Actions to check
                )
            )
            // Optionally add auxData or includeMeta here if needed
        )

        Log.d(APP_TAG, "Calling checkResources...")
        cerbosEmbeddedPDP.checkResources(request) { response ->
            // --- Handle the Response ---
            // This callback runs on the main thread
            Log.i(APP_TAG, "<<< CheckResources Response Received >>>")
            Log.d(APP_TAG, "Response Details: $response")

            // Process the response (log results, update UI, etc.)
            val allowedActions = mutableListOf<String>()
            val deniedActions = mutableListOf<String>()

            response.results.forEach { resultEntry ->
                resultEntry.actions.forEach { (action, effect) ->
                    val resourceId = resultEntry.resource.id
                    if (effect == CerbosEmbeddedPDP.Effect.EFFECT_ALLOW) {
                        allowedActions.add("$action on $resourceId")
                        Log.i(APP_TAG, "ALLOW: $action on resource ${resourceId}")
                    } else {
                        deniedActions.add("$action on $resourceId")
                        Log.w(APP_TAG, "DENY: $action on resource ${resourceId}")
                    }
                }
            }

            updateStatus("Check complete.\nAllowed: ${allowedActions.joinToString()}\nDenied: ${deniedActions.joinToString()}")
        }
    }

    // Helper to update the status TextView safely on the UI thread
    private fun updateStatus(message: String) {
        runOnUiThread {
            Log.d(APP_TAG, "Status Update: $message")
            binding.statusTextView.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cerbosEmbeddedPDP.isInitialized) { // Check if initialized
            cerbosEmbeddedPDP.destroy()
        }
    }

}