package dev.cerbos.epdp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer

// Wire types shared with `CerbosBridge/src/core.ts` and `bridge.ts`. Keep the two in sync.

@Serializable
internal enum class BridgeStatus {
    @SerialName("idle") IDLE,
    @SerialName("loading") LOADING,
    @SerialName("ready") READY,
    @SerialName("failed") FAILED,
}

@Serializable
internal data class BridgeEnvelope<T>(
    val ok: Boolean,
    val result: T? = null,
    val error: BridgeError? = null,
)

@Serializable
internal data class BridgeInitParams(
    val ruleId: String,
    val scopes: List<String>,
    val hub: Hub,
    val updateIntervalSeconds: Double,
    val activateOnLoad: Boolean,
    val initialLoadTimeoutSeconds: Double,
    val options: Options,
    val emitDecisions: Boolean,
    val jwtDecoding: Boolean,
    val cachedBundle: CachedBundle? = null,
) {
    @Serializable
    data class Hub(
        val baseUrl: String? = null,
        val clientId: String? = null,
        val clientSecret: String? = null,
    )

    @Serializable
    data class Options(
        val defaultPolicyVersion: String? = null,
        val defaultScope: String? = null,
        val globals: Map<String, JsonElement>? = null,
        val lenientScopeSearch: Boolean? = null,
        val schemaEnforcement: String? = null,
        val strictEvaluation: Boolean? = null,
        val userAgent: String? = null,
        val headers: Map<String, String>? = null,
        val onValidationError: String? = null,
    )

    @Serializable data class CachedBundle(val key: String, val body: String)
}

@Serializable
internal data class BridgeInitResult(
    val status: BridgeStatus,
    val bundle: BundleInfo? = null,
    val pending: BundleInfo? = null,
    val server: ServerInfo,
)

@Serializable
internal data class BridgeStatusResult(
    val status: BridgeStatus,
    val bundle: BundleInfo? = null,
    val pending: BundleInfo? = null,
    val error: BridgeError? = null,
)

/** Events pushed by the bridge through the `cerbosHost.postMessage` JavaScript interface. */
internal sealed class BridgeEvent {
    data object BridgeReady : BridgeEvent()

    data class Status(val status: BridgeStatus, val error: BridgeError?) : BridgeEvent()

    data class Bundles(val active: BundleInfo?, val pending: BundleInfo?) : BridgeEvent()

    data class PolicyUpdate(
        val ok: Boolean,
        val error: BridgeError?,
        val bundle: BundleInfo?,
        val pending: BundleInfo?,
    ) : BridgeEvent()

    data class Decision(val entry: JsonElement) : BridgeEvent()

    data class ValidationErrors(val errors: List<ValidationError>) : BridgeEvent()

    data class BundleCache(
        val key: String,
        val body: String,
        val bundleId: String,
        val ruleRevision: String,
    ) : BridgeEvent()

    data class Log(val level: String, val message: String) : BridgeEvent()

    data class JwtDecode(val id: String, val token: String, val keySetId: String) : BridgeEvent()

    data class Unknown(val type: String) : BridgeEvent()

    companion object {
        fun decode(json: String): BridgeEvent {
            val obj = BridgeJson.decode<JsonObject>(json)
            val type =
                obj.string("type") ?: throw CerbosException.InvalidResponse("event has no type")
            return when (type) {
                "bridgeReady" -> BridgeReady
                "status" -> Status(obj.required("status"), obj.optional("error"))
                "bundles" -> Bundles(obj.optional("active"), obj.optional("pending"))
                "policyUpdate" ->
                    PolicyUpdate(
                        ok = obj.required("ok"),
                        error = obj.optional("error"),
                        bundle = obj.optional("bundle"),
                        pending = obj.optional("pending"),
                    )
                "decision" -> Decision(obj["entry"] ?: JsonNull)
                "validationErrors" -> ValidationErrors(obj.optional("errors") ?: emptyList())
                "bundleCache" ->
                    BundleCache(
                        key = obj.required("key"),
                        body = obj.required("body"),
                        bundleId = obj.string("bundleId") ?: "",
                        ruleRevision = obj.string("ruleRevision") ?: "",
                    )
                "log" ->
                    Log(
                        level = obj.string("level") ?: "info",
                        message = obj.string("message") ?: "",
                    )
                "jwtDecode" ->
                    JwtDecode(
                        id = obj.required("id"),
                        token = obj.string("token") ?: "",
                        keySetId = obj.string("keySetId") ?: "",
                    )
                else -> Unknown(type)
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        private inline fun <reified T> JsonObject.optional(key: String): T? {
            val element = this[key] ?: return null
            if (element is JsonNull) return null
            return try {
                BridgeJson.json.decodeFromJsonElement(
                    BridgeJson.json.serializersModule.serializer<T>(),
                    element,
                )
            } catch (error: SerializationException) {
                throw CerbosException.InvalidResponse("event field $key: ${error.message}")
            } catch (error: IllegalArgumentException) {
                throw CerbosException.InvalidResponse("event field $key: ${error.message}")
            }
        }

        private inline fun <reified T> JsonObject.required(key: String): T =
            optional<T>(key) ?: throw CerbosException.InvalidResponse("event is missing $key")
    }
}

/** JSON coding configured for the bridge's wire format. */
internal object BridgeJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        // Omit nulls on encode and tolerate missing fields on decode; `@cerbos/core` treats both
        // the same.
        explicitNulls = false
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): String =
        try {
            json.encodeToString(json.serializersModule.serializer<T>(), value)
        } catch (error: SerializationException) {
            throw CerbosException.InvalidRequest(error.message ?: error.toString())
        } catch (error: IllegalArgumentException) {
            throw CerbosException.InvalidRequest(error.message ?: error.toString())
        }

    inline fun <reified T> decode(string: String): T =
        try {
            json.decodeFromString(json.serializersModule.serializer<T>(), string)
        } catch (error: SerializationException) {
            throw CerbosException.InvalidResponse(error.message ?: error.toString())
        } catch (error: IllegalArgumentException) {
            throw CerbosException.InvalidResponse(error.message ?: error.toString())
        }

    /** Unwraps a bridge envelope, mapping bridge-side failures with [failure]. */
    inline fun <reified T> unwrap(
        envelopeJSON: String,
        failure: (BridgeError) -> CerbosException,
    ): T {
        val envelope = decode<BridgeEnvelope<T>>(envelopeJSON)
        if (envelope.ok) {
            return envelope.result
                ?: throw CerbosException.InvalidResponse("envelope is missing its result")
        }
        throw failure(
            envelope.error ?: BridgeError(name = "Error", message = "unknown bridge error")
        )
    }

    /** A JavaScript string literal for [value] (JSON string syntax is a subset of JavaScript's). */
    fun jsLiteral(value: String): String = json.encodeToString(String.serializer(), value)
}
