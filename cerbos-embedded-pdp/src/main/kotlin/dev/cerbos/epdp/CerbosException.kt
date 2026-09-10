package dev.cerbos.epdp

import kotlinx.serialization.Serializable

/** Errors produced by the embedded policy decision point. */
public sealed class CerbosException(message: String) : Exception(message) {
    /** `start()` has not completed successfully, so checks cannot be evaluated. */
    public data object NotReady : CerbosException("The embedded PDP is not ready.")

    /**
     * The web view (or its renderer process) that hosts the WebAssembly module could not be started
     * or was lost.
     */
    public data class WebView(val reason: String) :
        CerbosException("The embedded PDP web view failed: $reason")

    /**
     * The bridge did not answer in time: the page did not become ready within the start timeout, or
     * a check or plan exceeded [CerbosEmbeddedPDP.Configuration.requestTimeout]. [operation] names
     * what timed out.
     */
    public data class Timeout(val operation: String) :
        CerbosException("Timed out waiting for the embedded PDP to $operation.")

    /**
     * The engine cannot run: WebAssembly is unavailable in the web view or the bundled
     * `server.wasm` failed to load or compile.
     */
    public data class Engine(val reason: String) :
        CerbosException("The embedded PDP engine cannot run: $reason")

    /**
     * Cerbos Hub rejected or could not serve the policy bundle (see [BridgeError.code] for the gRPC
     * status).
     */
    public data class PolicySource(val error: BridgeError) :
        CerbosException("Cerbos Hub could not provide the policy bundle: ${error.message}")

    /** The embedded client failed to initialise for another reason. */
    public data class Initialisation(val error: BridgeError) :
        CerbosException("Failed to initialise the embedded PDP: ${error.message}")

    /** The embedded PDP rejected the request (gRPC status semantics, see [BridgeError.code]). */
    public data class Request(val error: BridgeError) :
        CerbosException(error.details?.let { "${error.message} ($it)" } ?: error.message)

    /** The bridge returned a payload that could not be decoded. */
    public data class InvalidResponse(val reason: String) :
        CerbosException("Invalid response from the embedded PDP bridge: $reason")

    /**
     * A request could not be encoded for the bridge, or a configuration value (for example a
     * non-HTTPS `hubBaseUrl`) cannot be used by it.
     */
    public data class InvalidRequest(val reason: String) :
        CerbosException("Invalid request: $reason")

    /** Reading from or writing to the on-disk policy bundle cache failed. */
    public data class Cache(val reason: String) :
        CerbosException("Policy bundle cache error: $reason")

    /** Whether the failure means the web view is gone and a restart may recover. */
    internal val isWebViewFailure: Boolean
        get() = this is WebView

    internal companion object {
        /**
         * Maps a failed bridge `init` to the most specific error using the bridge's classification.
         */
        fun initialisationFailure(error: BridgeError): CerbosException =
            when (error.kind) {
                "engine" -> Engine(error.message)
                "policySource" -> PolicySource(error)
                else -> Initialisation(error)
            }
    }
}

/** An error raised inside the JavaScript bridge, serialised to a stable shape. */
@Serializable
public data class BridgeError(
    /**
     * `NotOK` (gRPC-style failure from the PDP), `ValidationFailed`, or a JavaScript error name.
     */
    val name: String,
    val message: String,
    /** `engine`, `policySource` or `bridge` for initialisation failures. */
    val kind: String? = null,
    /**
     * gRPC status code for `NotOK` errors (for example `3` for invalid argument, `14` for
     * unavailable).
     */
    val code: Int? = null,
    val details: String? = null,
    val validationErrors: List<ValidationError>? = null,
    val stack: String? = null,
)
