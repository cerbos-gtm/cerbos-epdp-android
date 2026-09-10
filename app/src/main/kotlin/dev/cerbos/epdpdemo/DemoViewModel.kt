package dev.cerbos.epdpdemo

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cerbos.epdp.CerbosEmbeddedPDP
import dev.cerbos.epdp.CerbosException
import dev.cerbos.epdp.CheckResult
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.launch

/**
 * Hub connection settings entered in the settings sheet. The secret is kept in the Keystore-backed
 * [SecretStore].
 */
data class HubSettings(
    val ruleId: String = DEFAULT_RULE_ID,
    val hubBaseUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
) {
    companion object {
        const val DEFAULT_RULE_ID = "AVGB9RP6HFBL"
        private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /**
         * Validates the optional Hub base URL before it reaches the PDP, so a bad value is reported
         * in the form instead of surfacing as a start failure. Returns a message naming the
         * offending value, or `null` when the text is empty or acceptable.
         */
        fun hubUrlProblem(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val uri =
                try {
                    java.net.URI(trimmed)
                } catch (_: java.net.URISyntaxException) {
                    null
                }
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host?.lowercase()
            if (uri == null || scheme == null || host.isNullOrEmpty()) {
                return "“$trimmed” is not a valid URL. Enter a full URL such as https://api.cerbos.cloud."
            }
            if (scheme != "https" && !(scheme == "http" && host in loopbackHosts)) {
                return "“$trimmed” must use https. Plain http is only allowed for localhost."
            }
            return null
        }
    }
}

/**
 * Owns the single [CerbosEmbeddedPDP] instance for the app (it survives configuration changes with
 * the view model) and the state of the demo screen.
 */
class DemoViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("cerbos.hub", Context.MODE_PRIVATE)
    private val secrets = SecretStore(application)

    var settings by mutableStateOf(loadSettings())
        private set

    var pdp by mutableStateOf<CerbosEmbeddedPDP?>(null)
        private set

    var scenario by mutableStateOf(DemoScenario())

    var lastResult by mutableStateOf<CheckResult?>(null)
        private set

    var lastDuration by mutableStateOf<Duration?>(null)
        private set

    var checkError by mutableStateOf<String?>(null)
        private set

    var isChecking by mutableStateOf(false)
        private set

    /** Problem persisting the last applied settings (for example a Keystore failure). */
    var settingsError by mutableStateOf<String?>(null)
        private set

    init {
        startPdp()
    }

    /** Persists the settings and (re)starts the PDP with them. */
    fun apply(settings: HubSettings) {
        val previousSecret = this.settings.clientSecret
        this.settings = settings
        settingsError = null
        preferences.edit {
            putString(KEY_RULE_ID, settings.ruleId)
            putString(KEY_HUB_BASE_URL, settings.hubBaseUrl)
            putString(KEY_CLIENT_ID, settings.clientId)
        }
        if (settings.clientSecret != previousSecret) {
            try {
                secrets.set(SecretStore.CLIENT_SECRET_KEY, settings.clientSecret)
            } catch (error: java.security.GeneralSecurityException) {
                settingsError =
                    "The client secret was not stored in the Keystore: ${error.message ?: error}"
            } catch (error: java.security.ProviderException) {
                settingsError =
                    "The client secret was not stored in the Keystore: ${error.message ?: error}"
            }
        }
        startPdp()
    }

    /**
     * First start creates the single PDP instance; later applies reconfigure it in place, which
     * reuses the already-compiled engine instead of rebuilding the web view.
     */
    private fun startPdp() {
        lastResult = null
        checkError = null
        val configuration = makeConfiguration()
        viewModelScope.launch {
            val existing = pdp
            if (existing != null) {
                existing.reconfigure(configuration)
            } else {
                val created = CerbosEmbeddedPDP(getApplication(), configuration)
                pdp = created
                created.start()
            }
        }
    }

    private fun makeConfiguration(): CerbosEmbeddedPDP.Configuration {
        val trimmedUrl = settings.hubBaseUrl.trim()
        val trimmedClientId = settings.clientId.trim()
        return CerbosEmbeddedPDP.Configuration(
            ruleId = settings.ruleId.trim(),
            // An invalid URL is rejected by the settings sheet; fall back to the default Hub here.
            hubBaseUrl =
                trimmedUrl.takeIf { it.isNotEmpty() && HubSettings.hubUrlProblem(it) == null },
            credentials =
                if (trimmedClientId.isNotEmpty() && settings.clientSecret.isNotEmpty()) {
                    CerbosEmbeddedPDP.HubCredentials(trimmedClientId, settings.clientSecret)
                } else {
                    null
                },
        )
    }

    fun retry() {
        viewModelScope.launch { pdp?.start() }
    }

    fun restart() {
        viewModelScope.launch { pdp?.restart() }
    }

    fun clearOfflineCache() {
        viewModelScope.launch { pdp?.clearOfflineCache() }
    }

    /**
     * Android may kill the WebView renderer while the app is in the background; verify and rebuild
     * on return instead of failing the first check.
     */
    fun checkHealth() {
        viewModelScope.launch { pdp?.checkHealth() }
    }

    fun runCheck() {
        val pdp = pdp ?: return
        if (isChecking) return
        isChecking = true
        checkError = null
        viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            try {
                val response = pdp.checkResources(scenario.request())
                lastDuration = started.elapsedNow()
                lastResult = response.result(DemoScenario.RESOURCE_KIND, "1")
            } catch (error: CerbosException) {
                checkError = error.message
            } finally {
                isChecking = false
            }
        }
    }

    override fun onCleared() {
        pdp?.close()
    }

    private fun loadSettings(): HubSettings =
        HubSettings(
            ruleId = preferences.getString(KEY_RULE_ID, null) ?: HubSettings.DEFAULT_RULE_ID,
            hubBaseUrl = preferences.getString(KEY_HUB_BASE_URL, null) ?: "",
            clientId = preferences.getString(KEY_CLIENT_ID, null) ?: "",
            clientSecret = secrets.get(SecretStore.CLIENT_SECRET_KEY) ?: "",
        )

    private companion object {
        const val KEY_RULE_ID = "cerbos.hub.ruleId"
        const val KEY_HUB_BASE_URL = "cerbos.hub.baseURL"
        const val KEY_CLIENT_ID = "cerbos.hub.clientId"
    }
}
