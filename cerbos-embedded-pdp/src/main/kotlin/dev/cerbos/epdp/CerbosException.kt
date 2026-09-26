package dev.cerbos.epdp

import kotlinx.serialization.Serializable

/** Errors produced by the embedded policy decision point. */
public sealed class CerbosException(message: String) : Exception(message) {
    /** The PDP is not started, so checks cannot run. */
    public data object NotReady : CerbosException("The embedded PDP is not ready.")

    /** The web view that hosts the engine could not start, or its renderer was lost. */
    public data class WebView(val reason: String) :
        CerbosException("The embedded PDP web view failed: $reason")

    /** [operation] did not finish in time. */
    public data class Timeout(val operation: String) :
        CerbosException("Timed out waiting for the embedded PDP to $operation.")

    /** WebAssembly is unavailable, or `server.wasm` failed to load or compile. */
    public data class Engine(val reason: String) :
        CerbosException("The embedded PDP engine cannot run: $reason")

    /**
     * Cerbos Hub rejected or could not serve the bundle. [BridgeError.code] has the gRPC status.
     */
    public data class PolicySource(val error: BridgeError) :
        CerbosException("Cerbos Hub could not provide the policy bundle: ${error.message}")

    /** Initialisation failed for another reason. */
    public data class Initialisation(val error: BridgeError) :
        CerbosException("Failed to initialise the embedded PDP: ${error.message}")

    /** The engine rejected the request. [BridgeError.code] has the gRPC status. */
    public data class Request(val error: BridgeError) :
        CerbosException(error.details?.let { "${error.message} ($it)" } ?: error.message)

    /** The bridge returned a payload that could not be decoded. */
    public data class InvalidResponse(val reason: String) :
        CerbosException("Invalid response from the embedded PDP bridge: $reason")

    /** A request could not be encoded, or a configuration value is invalid. */
    public data class InvalidRequest(val reason: String) :
        CerbosException("Invalid request: $reason")

    /** The offline bundle cache could not be read or written. */
    public data class Cache(val reason: String) :
        CerbosException("Policy bundle cache error: $reason")

    /** The web view is gone; a restart may recover. */
    internal val isWebViewFailure: Boolean
        get() = this is WebView

    internal companion object {
        /** Maps a failed `init` to the most specific exception. */
        fun initialisationFailure(error: BridgeError): CerbosException =
            when (error.kind) {
                "engine" -> Engine(error.message)
                "policySource" -> PolicySource(error)
                else -> Initialisation(error)
            }
    }
}

/** An error raised inside the JavaScript bridge. */
@Serializable
public data class BridgeError(
    /** `NotOK` (gRPC failure), `ValidationFailed`, or a JavaScript error name. */
    val name: String,
    val message: String,
    /** For init failures: `engine`, `policySource` or `bridge`. */
    val kind: String? = null,
    /** gRPC status for `NotOK` errors, for example `3` (invalid argument). */
    val code: Int? = null,
    val details: String? = null,
    val validationErrors: List<ValidationError>? = null,
    val stack: String? = null,
)
