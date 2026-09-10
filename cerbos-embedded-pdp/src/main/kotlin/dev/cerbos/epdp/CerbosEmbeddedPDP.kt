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
 * An embedded Cerbos policy decision point for Android.
 *
 * Runs the official `@cerbos/embedded-client` and the `@cerbos/embedded-server` WebAssembly module
 * inside a hidden web view, downloads policy bundles from Cerbos Hub, keeps them updated in the
 * background, and caches the last bundle on disk so the app can start offline.
 *
 * ```kotlin
 * val pdp = CerbosEmbeddedPDP(context, CerbosEmbeddedPDP.Configuration(ruleId = "AVGB9RP6HFBL"))
 * pdp.start()
 * val allowed = pdp.isAllowed(principal = alice, resource = document, action = "view")
 * ```
 *
 * State is exposed through [state]; every method may be called from any thread and switches to the
 * main thread internally. Share one instance per rule: each instance owns a web view and compiles
 * the 20 MB engine.
 */
public class CerbosEmbeddedPDP(
    context: Context,
    configuration: Configuration,
    private val cache: PolicyBundleCache = PolicyBundleCache(context),
) {
    // MARK: Configuration

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
         * ID of the embedded PDP rule from the deployment's "Embedded PDP rules" tab in Cerbos Hub.
         */
        val ruleId: String,
        /** Scopes to request when the rule requires (or allows) scoped bundles. */
        val scopes: List<String> = emptyList(),
        /**
         * Cerbos Hub API base URL. Defaults to `https://api.cerbos.cloud`. Must use `https`; plain
         * `http` is only accepted for `localhost`, because the bridge page's Content Security
         * Policy blocks every other insecure origin.
         */
        val hubBaseUrl: String? = null,
        /**
         * Client credentials for rules that require authentication. Keep them out of source
         * control.
         */
        val credentials: HubCredentials? = null,
        /**
         * How often to check Hub for policy updates. `null` disables polling. Minimum 10 seconds.
         */
        val updateInterval: Duration? = 60.seconds,
        /**
         * Whether downloaded updates replace the active bundle immediately (otherwise call
         * [activatePendingBundle]).
         */
        val activateOnLoad: Boolean = true,
        /** Upper bound for [start]: WebAssembly compilation plus the first bundle download. */
        val startTimeout: Duration = 120.seconds,
        /**
         * Upper bound for a single check or plan. Evaluation takes milliseconds; this only fires on
         * a stuck engine.
         */
        val requestTimeout: Duration = 30.seconds,
        /**
         * The first bundle download is aborted after this long so a stalled connection falls back
         * to the offline cache. Values under one second are raised to one second.
         */
        val initialLoadTimeout: Duration = 20.seconds,
        /**
         * When a check fails because the renderer process died, rebuild it and retry the check
         * once.
         */
        val retryAfterRecovery: Boolean = true,
        /**
         * How many automatic rebuilds are allowed within [restartWindow] before giving up until
         * [start], [restart] or [reconfigure] is called.
         */
        val maximumRestarts: Int = 3,
        val restartWindow: Duration = 600.seconds,
        val defaultPolicyVersion: String? = null,
        val defaultScope: String? = null,
        val globals: Map<String, JsonElement> = emptyMap(),
        val lenientScopeSearch: Boolean = false,
        val schemaEnforcement: SchemaEnforcement = SchemaEnforcement.NONE,
        val strictEvaluation: Boolean = false,
        val userAgent: String? = null,
        /** Extra headers sent with Hub requests. */
        val headers: Map<String, String> = emptyMap(),
        val validationErrors: ValidationErrorHandling = ValidationErrorHandling.IGNORE,
        /** Persist the latest policy bundle so the PDP can start without connectivity. */
        val offlineCache: Boolean = true,
        /**
         * Receives decision log entries (same shape as the JavaScript SDK's `DecisionLogEntry`).
         */
        val onDecision: ((JsonElement) -> Unit)? = null,
        /**
         * Receives validation errors when [validationErrors] is [ValidationErrorHandling.REPORT].
         */
        val onValidationErrors: ((List<ValidationError>) -> Unit)? = null,
        /** Verifies and decodes JWTs passed as auxiliary data. Required to use [AuxData.jwt]. */
        val jwtDecoder: (suspend (JWT) -> Map<String, JsonElement>)? = null,
    )

    public sealed interface Status {
        public data object Idle : Status

        public data object Loading : Status

        public data object Ready : Status

        public data class Failed(val error: CerbosException) : Status
    }

    public data class LogLine(val date: Instant, val level: String, val message: String)

    /** Everything observable about the PDP, published as one immutable snapshot. */
    public data class State(
        val status: Status = Status.Idle,
        /** The policy bundle decisions are currently evaluated against. */
        val bundle: BundleInfo? = null,
        /**
         * A newer bundle that has been downloaded but not activated (only when
         * [Configuration.activateOnLoad] is false).
         */
        val pendingBundle: BundleInfo? = null,
        /** Build information of the bundled WebAssembly server. */
        val server: ServerInfo? = null,
        /** Outcome of the most recent background update check. */
        val lastPolicyUpdate: PolicyUpdate? = null,
        /**
         * Recent diagnostics from the bridge (warnings, errors, cache activity). Capped at 100
         * lines.
         */
        val logs: List<LogLine> = emptyList(),
    ) {
        val isReady: Boolean
            get() = status == Status.Ready
    }

    // MARK: Observable state

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

    /** Key under which this configuration's policy bundle is cached. Matches the bridge's key. */
    public val offlineCacheKey: String
        get() = offlineCacheKey(configuration)

    private val host = CerbosWebViewHost(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Incremented whenever the web view or the configuration an attempt was started under is no
     * longer valid ([stop], [reconfigure], a renderer process termination). A start attempt that
     * began under an older generation abandons itself instead of publishing its outcome.
     */
    private var startGeneration = 0
    private var startJob: Deferred<Unit>? = null
    private var startJobGeneration = 0
    /** The generation under which [status] last became [Status.Ready]. */
    private var readyGeneration: Int? = null
    private val restartTimes = ArrayList<Instant>()

    init {
        host.onEvent = ::handle
        host.onProcessTerminated = ::handleProcessTermination
    }

    // MARK: Lifecycle

    /**
     * Loads the WebAssembly module and the policy bundle. Safe to call repeatedly; concurrent
     * callers share one attempt. Call again after a failure to retry; this also resets the
     * automatic restart budget ([Configuration.maximumRestarts]).
     *
     * Failures are reported through [state] (as [Status.Failed]) rather than by throwing.
     */
    public suspend fun start() {
        withContext(Dispatchers.Main.immediate) {
            restartTimes.clear()
            startIfNeeded()
        }
    }

    /**
     * Joins the in-flight start when it is still valid, otherwise launches a new attempt unless the
     * PDP is already ready under the current generation.
     */
    private suspend fun startIfNeeded() {
        // An attempt that stop(), reconfigure() or a process termination invalidated is left to
        // abandon itself (it may be blocked on the old web view) and is not waited for.
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
     * Applies a new configuration (rule, scopes, credentials, options) without rebuilding the web
     * view. The already-compiled engine module is reused, so this is much cheaper than [restart]. A
     * start that is still in progress is superseded, so the new configuration is the one that ends
     * up running.
     */
    public suspend fun reconfigure(configuration: Configuration) {
        withContext(Dispatchers.Main.immediate) {
            this@CerbosEmbeddedPDP.configuration = configuration
            startGeneration++
            restartTimes.clear()
            startIfNeeded()
        }
    }

    /** Discards the current web view and starts again from scratch, recompiling the engine. */
    public suspend fun restart() {
        withContext(Dispatchers.Main.immediate) {
            stop()
            start()
        }
    }

    /**
     * Verifies the renderer process is still answering. Call it when the app returns to the
     * foreground: Android may have killed the WebView renderer while the app was in the background,
     * and this rebuilds it before the first real check instead of failing that check.
     *
     * @return `true` when the PDP is ready after the check.
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

    /**
     * Releases the web view and cancels background work. The instance cannot be started again. Safe
     * to call from any thread, including from inside a coroutine.
     */
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

    // MARK: Checks

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

    // MARK: Internals

    /**
     * Runs a bridge call, and if the renderer process died underneath it, rebuilds once and
     * retries.
     */
    private suspend inline fun <reified P, reified R> perform(method: String, params: P): R =
        withContext(Dispatchers.Main.immediate) {
            ensureReady()
            val paramsJSON = BridgeJson.encode(params)
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
                // Rebuild even if the termination notice has not arrived yet; if it has, the host
                // already knows the bridge is gone and this joins the restart it triggered.
                if (host.isBridgeReady) shutdown()
                startIfNeeded()
                ensureReady()
                invoke<R>(method, paramsJSON, configuration.requestTimeout) {
                    CerbosException.Request(it)
                }
            }
        }

    private suspend inline fun <reified R> invoke(
        method: String,
        paramsJSON: String?,
        timeout: Duration?,
        noinline failure: (BridgeError) -> CerbosException,
    ): R = BridgeJson.unwrap(host.call(method, paramsJSON, timeout), failure)

    private fun ensureReady() {
        if (status != Status.Ready) throw CerbosException.NotReady
    }

    /**
     * One start attempt. [generation] is the value of [startGeneration] when the attempt was
     * launched; after every suspension the attempt checks it is still current before touching
     * state.
     */
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
            if (generation != startGeneration) {
                // Superseded by stop(), reconfigure() or a restart; that attempt reports its own
                // outcome.
                return
            }
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
            // Zero means "no timeout" in the bridge, so never let a short duration round down to
            // it.
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
                // `init` reports its own outcome; this covers later failures (for example after a
                // restart of the page).
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
        // Android may reclaim the renderer process while the app is in the background.
        // Rebuild it (the offline cache makes this cheap even without connectivity), within a
        // budget.
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
        private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /**
         * Hub must be reachable from the bridge page, whose Content Security Policy only allows
         * `https:` and loopback `http:` connections. Rejecting other URLs here gives a
         * configuration error instead of an opaque network failure from inside the page.
         */
        internal fun validateHubBaseUrl(url: String?): CerbosException? {
            if (url == null) return null
            val invalid =
                CerbosException.InvalidRequest("hubBaseUrl $url must be an absolute https URL")
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
                        "hubBaseUrl $url must use https (plain http is only allowed for localhost)"
                    )
            }
        }

        /**
         * Key under which the policy bundle for [configuration] is cached. Keep in sync with
         * `bundleCacheKey` in core.ts.
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
