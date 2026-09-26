package dev.cerbos.epdp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * A Cerbos policy decision point that evaluates policies on the device.
 *
 * Policies come from a Cerbos Hub embedded PDP rule, are refreshed in the background, and are
 * cached on disk so the app can start offline. The engine runs in a hidden web view.
 *
 * ```kotlin
 * val pdp = CerbosEmbeddedPDP(context, CerbosEmbeddedPDP.Configuration(ruleId = "AVGB9RP6HFBL"))
 * pdp.start()
 * val allowed = pdp.isAllowed(principal = alice, resource = document, action = "view")
 * ```
 *
 * Methods can be called from any thread. Create one instance per rule and share it: each one owns a
 * web view and a compiled copy of the 20 MB engine.
 */
public class CerbosEmbeddedPDP(
    context: Context,
    configuration: Configuration,
    private val cache: PolicyBundleCache = PolicyBundleCache(context),
) {
    public data class HubCredentials(val clientId: String, val clientSecret: String) {
        override fun toString(): String = "HubCredentials(clientId=$clientId, clientSecret=***)"
    }

    /** Schema enforcement level for inputs (see the Cerbos schema documentation). */
    public enum class SchemaEnforcement(internal val wireValue: String) {
        NONE("none"),
        WARN("warn"),
        REJECT("reject"),
    }

    public enum class ValidationErrorHandling(internal val wireValue: String?) {
        /** Validation errors are only included in results. */
        IGNORE(null),
        /** Validation errors are also delivered to [Configuration.onValidationErrors]. */
        REPORT("report"),
        /** Checks with validation errors fail with [CerbosException.Request]. */
        THROW("throw"),
    }

    public data class Configuration(
        /**
         * The embedded PDP rule ID, from the deployment's "Embedded PDP rules" tab in Cerbos Hub.
         */
        val ruleId: String,
        val scopes: List<String> = emptyList(),
        /** Defaults to `https://api.cerbos.cloud`. Must be `https` (or `http` on localhost). */
        val hubBaseUrl: String? = null,
        /** Only for rules that require authentication. Keep them out of source control. */
        val credentials: HubCredentials? = null,
        /** How often to poll Hub for policy updates (minimum 10 s). `null` disables polling. */
        val updateInterval: Duration? = 60.seconds,
        /**
         * `false` holds downloaded updates in [State.pendingBundle] until [activatePendingBundle].
         */
        val activateOnLoad: Boolean = true,
        /** Limit for [start]: compiling the engine plus the first bundle download. */
        val startTimeout: Duration = 120.seconds,
        /** Limit for a single check or plan. */
        val requestTimeout: Duration = 30.seconds,
        /** After this long, a stalled first download gives up and the offline cache is used. */
        val initialLoadTimeout: Duration = 20.seconds,
        /** Retry a check once if it failed because the web view's renderer process died. */
        val retryAfterRecovery: Boolean = true,
        /** Automatic renderer rebuilds allowed per [restartWindow]. */
        val maximumRestarts: Int = 3,
        val restartWindow: Duration = 600.seconds,
        val defaultPolicyVersion: String? = null,
        val defaultScope: String? = null,
        val globals: Map<String, JsonElement> = emptyMap(),
        val lenientScopeSearch: Boolean = false,
        val schemaEnforcement: SchemaEnforcement = SchemaEnforcement.NONE,
        val strictEvaluation: Boolean = false,
        val userAgent: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val validationErrors: ValidationErrorHandling = ValidationErrorHandling.IGNORE,
        /** Save each downloaded bundle so the next start works offline. */
        val offlineCache: Boolean = true,
        /** Receives decision log entries (the JavaScript SDK's `DecisionLogEntry` shape). */
        val onDecision: ((JsonElement) -> Unit)? = null,
        /** Used when [validationErrors] is [ValidationErrorHandling.REPORT]. */
        val onValidationErrors: ((List<ValidationError>) -> Unit)? = null,
        /** Verifies a JWT passed in [AuxData.jwt] and returns its claims. */
        val jwtDecoder: (suspend (JWT) -> Map<String, JsonElement>)? = null,
    )

    public sealed interface Status {
        public data object Idle : Status

        public data object Loading : Status

        public data object Ready : Status

        public data class Failed(val error: CerbosException) : Status
    }

    public data class LogLine(val date: Instant, val level: String, val message: String)

    public data class State(
        val status: Status = Status.Idle,
        /** The bundle decisions are evaluated against. */
        val bundle: BundleInfo? = null,
        /** A downloaded update waiting for [activatePendingBundle]. */
        val pendingBundle: BundleInfo? = null,
        val server: ServerInfo? = null,
        val lastPolicyUpdate: PolicyUpdate? = null,
        /** The last 100 diagnostic messages. */
        val logs: List<LogLine> = emptyList(),
    ) {
        val isReady: Boolean
            get() = status == Status.Ready
    }

    private val _state = MutableStateFlow(State())
    public val state: StateFlow<State> = _state.asStateFlow()

    public var configuration: Configuration = configuration
        private set

    public val status: Status
        get() = _state.value.status

    public val bundle: BundleInfo?
        get() = _state.value.bundle

    public val pendingBundle: BundleInfo?
        get() = _state.value.pendingBundle

    public val server: ServerInfo?
        get() = _state.value.server

    public val lastPolicyUpdate: PolicyUpdate?
        get() = _state.value.lastPolicyUpdate

    public val logs: List<LogLine>
        get() = _state.value.logs

    public val isReady: Boolean
        get() = _state.value.isReady

    /** The offline cache key for the current configuration. */
    public val offlineCacheKey: String
        get() = offlineCacheKey(configuration)

    private val host = CerbosWebViewHost(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Bumped by [stop], [reconfigure] and renderer loss. A start attempt from an older generation
     * drops its result.
     */
    private var startGeneration = 0
    private var startJob: Deferred<Unit>? = null
    private var startJobGeneration = 0
    private var readyGeneration: Int? = null
    private val restartTimes = ArrayList<Instant>()

    init {
        host.onEvent = ::handle
        host.onProcessTerminated = ::handleProcessTermination
    }

    /**
     * Loads the engine and the policy bundle. Idempotent; call again after a failure to retry.
     * Failures are reported as [Status.Failed] in [state], not thrown.
     */
    public suspend fun start() {
        withContext(Dispatchers.Main.immediate) {
            restartTimes.clear()
            startIfNeeded()
        }
    }

    /** Joins the current start attempt, or launches one unless already ready. */
    private suspend fun startIfNeeded() {
        // Attempts from an older generation abandon themselves; don't wait for them.
        while (true) {
            val inFlight = startJob
            if (inFlight == null || startJobGeneration != startGeneration) break
            inFlight.await()
        }
        if (status == Status.Ready && readyGeneration == startGeneration) return
        if (!scope.isActive) {
            _state.update { it.copy(status = Status.Failed(CerbosException.WebView("closed"))) }
            return
        }
        val generation = startGeneration
        val job = scope.async {
            performStart(generation)
            if (startJobGeneration == generation) startJob = null
        }
        startJob = job
        startJobGeneration = generation
        job.await()
    }

    /**
     * Switches to a new configuration, reusing the compiled engine. Much cheaper than [restart]. A
     * start in progress is superseded.
     */
    public suspend fun reconfigure(configuration: Configuration) {
        withContext(Dispatchers.Main.immediate) {
            this@CerbosEmbeddedPDP.configuration = configuration
            startGeneration++
            restartTimes.clear()
            startIfNeeded()
        }
    }

    /** Rebuilds the web view and recompiles the engine. */
    public suspend fun restart() {
        withContext(Dispatchers.Main.immediate) {
            stop()
            start()
        }
    }

    /**
     * Pings the engine and rebuilds it if Android killed the renderer while the app was in the
     * background. Call it when the app returns to the foreground.
     *
     * @return `true` if the PDP is ready.
     */
    public suspend fun checkHealth(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            when (status) {
                Status.Ready -> {
                    try {
                        invoke<BridgeStatusResult>("status", null, 5.seconds) {
                            CerbosException.Initialisation(it)
                        }
                        true
                    } catch (error: CerbosException) {
                        appendLog(
                            "warn",
                            "Health check failed (${error.message}); rebuilding the web view",
                        )
                        stop()
                        startIfNeeded()
                        status == Status.Ready
                    }
                }
                is Status.Failed -> {
                    startIfNeeded()
                    status == Status.Ready
                }
                Status.Idle,
                Status.Loading -> false
            }
        }

    /** Stops update polling and releases the web view. [start] brings the PDP back. */
    public suspend fun stop() {
        withContext(Dispatchers.Main.immediate) { shutdown() }
    }

    /** Releases everything. The instance cannot be started again. */
    public fun close() {
        scope.cancel()
        if (Looper.myLooper() == Looper.getMainLooper()) shutdown()
        else mainHandler.post { shutdown() }
    }

    private fun shutdown() {
        startGeneration++
        host.shutdown()
        _state.update { it.copy(status = Status.Idle, bundle = null, pendingBundle = null) }
    }

    /** Activates a downloaded-but-pending bundle when [Configuration.activateOnLoad] is `false`. */
    public suspend fun activatePendingBundle() {
        withContext(Dispatchers.Main.immediate) {
            invoke<JsonElement>("activate", null, configuration.requestTimeout) {
                CerbosException.Request(it)
            }
        }
    }

    /** Removes the cached policy bundle for this configuration. */
    public suspend fun clearOfflineCache() {
        cache.remove(offlineCacheKey)
    }

    public suspend fun checkResources(request: CheckResourcesRequest): CheckResourcesResponse =
        perform("checkResources", request)

    public suspend fun checkResource(request: CheckResourceRequest): CheckResult =
        perform("checkResource", request)

    public suspend fun isAllowed(request: IsAllowedRequest): Boolean = perform("isAllowed", request)

    public suspend fun isAllowed(
        principal: Principal,
        resource: Resource,
        action: String,
        auxData: AuxData? = null,
    ): Boolean = isAllowed(IsAllowedRequest(principal, resource, action, auxData))

    public suspend fun planResources(request: PlanResourcesRequest): PlanResourcesResponse =
        perform("planResources", request)

    /** Runs a bridge call, rebuilding and retrying once if the renderer died during it. */
    private suspend inline fun <reified P, reified R> perform(method: String, params: P): R {
        val paramsJSON = BridgeJson.encode(params)
        return withContext(Dispatchers.Main.immediate) {
            ensureReady()
            try {
                invoke<R>(method, paramsJSON, configuration.requestTimeout) {
                    CerbosException.Request(it)
                }
            } catch (error: CerbosException) {
                if (!error.isWebViewFailure || !configuration.retryAfterRecovery) throw error
                appendLog(
                    "warn",
                    "$method failed because the web view is gone (${error.message}); recovering and retrying once",
                )
                // The termination notice may not have arrived yet; if it has, this joins the
                // restart it triggered.
                if (host.isBridgeReady) shutdown()
                startIfNeeded()
                ensureReady()
                invoke<R>(method, paramsJSON, configuration.requestTimeout) {
                    CerbosException.Request(it)
                }
            }
        }
    }

    private suspend inline fun <reified R> invoke(
        method: String,
        paramsJSON: String?,
        timeout: Duration?,
        noinline failure: (BridgeError) -> CerbosException,
    ): R = host.call(method, paramsJSON, timeout) { BridgeJson.unwrap<R>(it, failure) }

    private fun ensureReady() {
        if (status != Status.Ready) throw CerbosException.NotReady
    }

    /** One start attempt. Checks after each suspension that [generation] is still current. */
    private suspend fun performStart(generation: Int) {
        host.verifyBundledResources()?.let { error ->
            _state.update { it.copy(status = Status.Failed(error)) }
            return
        }
        validateHubBaseUrl(configuration.hubBaseUrl)?.let { error ->
            _state.update { it.copy(status = Status.Failed(error)) }
            return
        }
        _state.update { it.copy(status = Status.Loading) }
        scope.launch(Dispatchers.Default) { BridgeJson.warmUp }
        try {
            host.start()
            if (generation != startGeneration) return

            var cachedBundle: BridgeInitParams.CachedBundle? = null
            if (configuration.offlineCache) {
                cache.load(offlineCacheKey)?.let { cached ->
                    cachedBundle =
                        BridgeInitParams.CachedBundle(
                            key = offlineCacheKey,
                            body = Base64.encodeToString(cached.body, Base64.NO_WRAP),
                        )
                    appendLog(
                        "info",
                        "Offline cache has bundle ${cached.entry.bundleId} from ${cached.entry.savedAt}",
                    )
                }
            }
            if (generation != startGeneration) return

            val result =
                invoke<BridgeInitResult>(
                    "init",
                    BridgeJson.encode(initParams(cachedBundle)),
                    configuration.startTimeout,
                ) {
                    CerbosException.initialisationFailure(it)
                }
            if (generation != startGeneration) return
            _state.update {
                it.copy(
                    status = Status.Ready,
                    bundle = result.bundle,
                    pendingBundle = result.pending,
                    server = result.server,
                )
            }
            readyGeneration = generation
            CerbosLog.info(
                "Cerbos embedded PDP ready (bundle ${result.bundle?.bundleId ?: "?"}, source ${result.bundle?.source ?: "?"})"
            )
        } catch (error: CerbosException) {
            if (generation != startGeneration) return
            CerbosLog.error("Cerbos embedded PDP failed to start: ${error.message}")
            _state.update { it.copy(status = Status.Failed(error)) }
        }
    }

    private fun initParams(cachedBundle: BridgeInitParams.CachedBundle?): BridgeInitParams {
        val configuration = configuration
        val intervalSeconds =
            configuration.updateInterval?.let { maxOf(10.0, it.toDouble(DurationUnit.SECONDS)) }
                ?: 0.0
        return BridgeInitParams(
            ruleId = configuration.ruleId.trim(),
            scopes = configuration.scopes,
            hub =
                BridgeInitParams.Hub(
                    baseUrl = configuration.hubBaseUrl,
                    clientId = configuration.credentials?.clientId,
                    clientSecret = configuration.credentials?.clientSecret,
                ),
            updateIntervalSeconds = intervalSeconds,
            activateOnLoad = configuration.activateOnLoad,
            // Zero means "no timeout" in the bridge.
            initialLoadTimeoutSeconds =
                maxOf(1.0, configuration.initialLoadTimeout.toDouble(DurationUnit.SECONDS)),
            options =
                BridgeInitParams.Options(
                    defaultPolicyVersion = configuration.defaultPolicyVersion,
                    defaultScope = configuration.defaultScope,
                    globals = configuration.globals.ifEmpty { null },
                    lenientScopeSearch = configuration.lenientScopeSearch,
                    schemaEnforcement = configuration.schemaEnforcement.wireValue,
                    strictEvaluation = configuration.strictEvaluation,
                    userAgent = configuration.userAgent,
                    headers = configuration.headers.ifEmpty { null },
                    onValidationError = configuration.validationErrors.wireValue,
                ),
            emitDecisions = configuration.onDecision != null,
            jwtDecoding = configuration.jwtDecoder != null,
            cachedBundle = cachedBundle,
        )
    }

    private fun handle(event: BridgeEvent) {
        when (event) {
            BridgeEvent.BridgeReady -> Unit

            is BridgeEvent.Status -> {
                // `init` reports its own outcome; this covers failures after that.
                if (
                    event.status == BridgeStatus.FAILED &&
                        status == Status.Ready &&
                        event.error != null
                ) {
                    _state.update {
                        it.copy(status = Status.Failed(CerbosException.Initialisation(event.error)))
                    }
                }
            }

            is BridgeEvent.Bundles -> {
                val active = event.active
                if (active != null && active != bundle) {
                    appendLog(
                        "info",
                        "Policy bundle ${active.bundleId} (revision ${active.ruleRevision}) active, loaded from " +
                            active.source.name.lowercase(),
                    )
                }
                val pending = event.pending
                if (pending != null && pending != pendingBundle) {
                    appendLog(
                        "info",
                        "Policy bundle ${pending.bundleId} downloaded; call activatePendingBundle() to use it",
                    )
                }
                _state.update { it.copy(bundle = active, pendingBundle = pending) }
            }

            is BridgeEvent.PolicyUpdate -> {
                _state.update {
                    it.copy(
                        lastPolicyUpdate =
                            PolicyUpdate(
                                date = Instant.now(),
                                error = event.error,
                                bundle = event.bundle,
                            ),
                        bundle = event.bundle ?: it.bundle,
                        pendingBundle = event.pending,
                    )
                }
                if (!event.ok && event.error != null) {
                    appendLog("warn", "Policy update failed: ${event.error.message}")
                }
            }

            is BridgeEvent.Decision -> configuration.onDecision?.invoke(event.entry)

            is BridgeEvent.ValidationErrors ->
                configuration.onValidationErrors?.invoke(event.errors)

            is BridgeEvent.BundleCache -> {
                if (!configuration.offlineCache) return
                val data =
                    try {
                        Base64.decode(event.body, Base64.DEFAULT)
                    } catch (_: IllegalArgumentException) {
                        appendLog(
                            "error",
                            "Failed to cache policy bundle: the bridge sent invalid base64",
                        )
                        return
                    }
                scope.launch {
                    try {
                        cache.save(event.key, data, event.bundleId, event.ruleRevision)
                        appendLog(
                            "debug",
                            "Cached policy bundle ${event.bundleId} (${data.size} bytes) for offline start",
                        )
                    } catch (error: CerbosException) {
                        appendLog("error", "Failed to cache policy bundle: ${error.message}")
                    }
                }
            }

            is BridgeEvent.Log -> appendLog(event.level, event.message)

            is BridgeEvent.JwtDecode -> decodeJWT(event.id, event.token, event.keySetId)

            is BridgeEvent.Unknown -> appendLog("warn", "Unknown bridge event ${event.type}")
        }
    }

    private fun decodeJWT(id: String, token: String, keySetId: String) {
        val decoder = configuration.jwtDecoder
        if (decoder == null) {
            host.resolveCallback(
                id,
                payloadJSON = null,
                errorMessage = "No jwtDecoder is configured",
            )
            return
        }
        scope.launch {
            try {
                val claims = decoder(JWT(token, keySetId.ifEmpty { null }))
                host.resolveCallback(
                    id,
                    payloadJSON = BridgeJson.encode(claims),
                    errorMessage = null,
                )
            } catch (error: Exception) {
                host.resolveCallback(id, payloadJSON = null, errorMessage = error.toString())
            }
        }
    }

    private fun handleProcessTermination() {
        // Android may reclaim the renderer in the background. Rebuild it, within a budget.
        startGeneration++
        _state.update {
            it.copy(
                status = Status.Failed(CerbosException.WebView("renderer process terminated")),
                bundle = null,
                pendingBundle = null,
            )
        }

        val now = Instant.now()
        val windowStart = now.minusMillis(configuration.restartWindow.inWholeMilliseconds)
        restartTimes.removeAll { it.isBefore(windowStart) }
        if (restartTimes.size >= configuration.maximumRestarts) {
            appendLog(
                "error",
                "Renderer process terminated ${restartTimes.size} times within ${configuration.restartWindow}; " +
                    "not restarting automatically. Call start() to retry.",
            )
            return
        }
        restartTimes.add(now)
        appendLog(
            "warn",
            "Renderer process terminated; restarting (${restartTimes.size}/${configuration.maximumRestarts} in the current window)",
        )
        scope.launch { startIfNeeded() }
    }

    private fun appendLog(level: String, message: String) {
        when (level) {
            "error" -> CerbosLog.error(message)
            "warn" -> CerbosLog.warn(message)
            else -> CerbosLog.debug(message)
        }
        _state.update {
            val logs = it.logs + LogLine(Instant.now(), level, message)
            it.copy(logs = if (logs.size > 100) logs.takeLast(100) else logs)
        }
    }

    public companion object {
        // Must match the `http:` hosts allowed by the page's Content Security Policy.
        private val loopbackHosts = setOf("localhost", "127.0.0.1")

        /**
         * Returns why [url] cannot be used as `hubBaseUrl`, or `null` if it can. The bridge page
         * only allows `https` and loopback `http`, so anything else would fail later with an opaque
         * network error.
         */
        public fun validateHubBaseUrl(url: String?): CerbosException? {
            if (url == null) return null
            val invalid =
                CerbosException.InvalidRequest(
                    "“$url” is not a valid URL. Enter a full URL such as https://api.cerbos.cloud."
                )
            val uri =
                try {
                    URI(url)
                } catch (_: URISyntaxException) {
                    return invalid
                }
            val scheme = uri.scheme?.lowercase() ?: return invalid
            val host = uri.host?.lowercase()
            if (host.isNullOrEmpty()) return invalid
            return when {
                scheme == "https" -> null
                scheme == "http" && host in loopbackHosts -> null
                else ->
                    CerbosException.InvalidRequest(
                        "“$url” must use https. Plain http is only allowed for localhost."
                    )
            }
        }

        /**
         * The offline cache key for [configuration]. Keep in sync with `bundleCacheKey` in core.ts.
         */
        public fun offlineCacheKey(configuration: Configuration): String {
            val baseUrl = (configuration.hubBaseUrl ?: "https://api.cerbos.cloud").trimEnd('/')
            return listOf(
                    baseUrl,
                    configuration.ruleId.trim(),
                    configuration.scopes.sorted().joinToString(","),
                )
                .joinToString("|")
        }
    }
}
