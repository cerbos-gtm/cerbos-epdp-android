package dev.cerbos.epdp

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Requests and responses mirror the `@cerbos/core` JavaScript API field for field (`attr` is
// `attributes` in Kotlin). Build attribute values with [attributesOf].

/**
 * Builds an attribute map: `attributesOf("tier" to "PREMIUM", "level" to 3, "beta" to true)`.
 *
 * Values can be `null`, booleans, numbers, strings, enums, [JsonElement]s, maps, lists and arrays.
 */
public fun attributesOf(vararg pairs: Pair<String, Any?>): Map<String, JsonElement> =
    pairs.associate { (key, value) ->
        key to value.toJsonElement()
    }

/** Converts a value to a [JsonElement]. See [attributesOf] for the supported types. */
public fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Enum<*> -> JsonPrimitive(name)
        is Map<*, *> ->
            JsonObject(
                entries.associate { (key, value) -> key.toString() to value.toJsonElement() }
            )
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        is Array<*> -> JsonArray(map { it.toJsonElement() })
        else ->
            throw IllegalArgumentException(
                "Unsupported attribute value type: ${this::class.java.name}"
            )
    }

@Serializable
public data class Principal(
    val id: String,
    val roles: List<String>,
    @SerialName("attr") val attributes: Map<String, JsonElement> = emptyMap(),
    val policyVersion: String? = null,
    val scope: String? = null,
)

@Serializable
public data class Resource(
    val kind: String,
    val id: String,
    @SerialName("attr") val attributes: Map<String, JsonElement> = emptyMap(),
    val policyVersion: String? = null,
    val scope: String? = null,
)

/** A resource and the actions to check on it. */
@Serializable public data class ResourceCheck(val resource: Resource, val actions: List<String>)

/** A JWT passed as auxiliary data. Your `jwtDecoder` verifies it; the engine only sees claims. */
@Serializable public data class JWT(val token: String, val keySetId: String? = null)

@Serializable public data class AuxData(val jwt: JWT? = null)

@Serializable
public data class CheckResourcesRequest(
    val principal: Principal,
    val resources: List<ResourceCheck>,
    val auxData: AuxData? = null,
    val includeMetadata: Boolean = false,
    val requestId: String? = null,
)

@Serializable
public data class CheckResourceRequest(
    val principal: Principal,
    val resource: Resource,
    val actions: List<String>,
    val auxData: AuxData? = null,
    val includeMetadata: Boolean = false,
    val requestId: String? = null,
)

@Serializable
public data class IsAllowedRequest(
    val principal: Principal,
    val resource: Resource,
    val action: String,
    val auxData: AuxData? = null,
    val requestId: String? = null,
)

/** The resource kind, and any known attributes, to plan a query for. */
@Serializable
public data class PlanResource(
    val kind: String,
    @SerialName("attr") val attributes: Map<String, JsonElement> = emptyMap(),
    val policyVersion: String? = null,
    val scope: String? = null,
)

@Serializable
public data class PlanResourcesRequest(
    val principal: Principal,
    val resource: PlanResource,
    val actions: List<String>,
    val auxData: AuxData? = null,
    val includeMetadata: Boolean = false,
    val requestId: String? = null,
) {
    public constructor(
        principal: Principal,
        resource: PlanResource,
        action: String,
        auxData: AuxData? = null,
        includeMetadata: Boolean = false,
        requestId: String? = null,
    ) : this(principal, resource, listOf(action), auxData, includeMetadata, requestId)
}

@Serializable(with = EffectSerializer::class)
public enum class Effect(public val wireValue: String) {
    ALLOW("EFFECT_ALLOW"),
    DENY("EFFECT_DENY"),
}

/** Unknown effects decode as [Effect.DENY] so one odd value doesn't fail a whole batch. */
internal object EffectSerializer : KSerializer<Effect> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Effect", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Effect) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): Effect {
        val raw = decoder.decodeString()
        return Effect.entries.firstOrNull { it.wireValue == raw }
            ?: run {
                CerbosLog.error("Unknown effect $raw in a check result; treating it as a denial")
                Effect.DENY
            }
    }
}

@Serializable
public data class ResourceIdentifier(
    val kind: String,
    val id: String,
    val policyVersion: String? = null,
    val scope: String? = null,
)

@Serializable
public data class ValidationError(
    val path: String,
    val message: String,
    /** `SOURCE_PRINCIPAL` or `SOURCE_RESOURCE`. */
    val source: String,
)

/** A value produced by a policy rule's `output` expression. */
@Serializable public data class OutputResult(val source: String, val value: JsonElement)

@Serializable public data class EffectMetadata(val matchedPolicy: String, val matchedScope: String)

@Serializable
public data class CheckResultMetadata(
    val actions: Map<String, EffectMetadata> = emptyMap(),
    val effectiveDerivedRoles: List<String> = emptyList(),
)

/** The decision for one resource. */
@Serializable
public data class CheckResult(
    val resource: ResourceIdentifier,
    val actions: Map<String, Effect> = emptyMap(),
    val validationErrors: List<ValidationError> = emptyList(),
    /** Only populated when the request set `includeMetadata`. */
    val metadata: CheckResultMetadata? = null,
    val outputs: List<OutputResult> = emptyList(),
) {
    /** Whether the action is allowed. Unknown actions are treated as denied. */
    public fun isAllowed(action: String): Boolean = actions[action] == Effect.ALLOW

    public val allowedActions: List<String>
        get() = actions.filterValues { it == Effect.ALLOW }.keys.sorted()

    public val allAllowed: Boolean
        get() = actions.isNotEmpty() && actions.values.all { it == Effect.ALLOW }
}

@Serializable
public data class CheckResourcesResponse(
    val requestId: String = "",
    val cerbosCallId: String = "",
    val results: List<CheckResult> = emptyList(),
) {
    public fun result(kind: String, id: String): CheckResult? = results.firstOrNull {
        it.resource.kind == kind && it.resource.id == id
    }

    /** Whether the action is allowed. Missing resources count as denied. */
    public fun isAllowed(kind: String, id: String, action: String): Boolean =
        result(kind, id)?.isAllowed(action) ?: false
}

@Serializable
public enum class PlanKind {
    @SerialName("KIND_ALWAYS_ALLOWED") ALWAYS_ALLOWED,
    @SerialName("KIND_ALWAYS_DENIED") ALWAYS_DENIED,
    @SerialName("KIND_CONDITIONAL") CONDITIONAL,
}

@Serializable
public data class PlanResourcesResponse(
    val requestId: String = "",
    val cerbosCallId: String = "",
    val kind: PlanKind,
    /** Filter expression when [kind] is [PlanKind.CONDITIONAL] (the JS SDK's `PlanExpression`). */
    val condition: JsonElement? = null,
    val validationErrors: List<ValidationError> = emptyList(),
    val metadata: JsonElement? = null,
)

@Serializable
public enum class BundleSource {
    /** Downloaded from Cerbos Hub during this session. */
    @SerialName("network") NETWORK,
    /** Loaded from the offline cache because the first download failed. */
    @SerialName("cache") CACHE,
}

/** Identifies the policy bundle currently loaded in the embedded PDP. */
@Serializable
public data class BundleInfo(
    val bundleId: String,
    val ruleRevision: String,
    val source: BundleSource,
    @Serializable(with = InstantSerializer::class) val receivedAt: Instant,
)

/** Build information for the bundled engine. */
@Serializable
public data class ServerInfo(
    val version: String,
    val commit: String,
    @Serializable(with = InstantSerializer::class) val builtAt: Instant,
)

/** Outcome of a background policy bundle update check. */
public data class PolicyUpdate(
    val date: Instant,
    val error: BridgeError?,
    val bundle: BundleInfo?,
) {
    val succeeded: Boolean
        get() = error == null
}

/** ISO 8601 timestamps, as produced by JavaScript's `Date.toISOString()`. */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
