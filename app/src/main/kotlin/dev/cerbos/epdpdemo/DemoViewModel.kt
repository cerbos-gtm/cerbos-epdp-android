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

/** Hub connection settings from the settings sheet. The secret is stored by [SecretStore]. */
data class HubSettings(
    val ruleId: String = DEFAULT_RULE_ID,
    val hubBaseUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
) {
    companion object {
        const val DEFAULT_RULE_ID = "AVGB9RP6HFBL"

        /** Why the optional Hub URL can't be used, or `null` if it's empty or fine. */
        fun hubUrlProblem(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return (CerbosEmbeddedPDP.validateHubBaseUrl(trimmed)
                    as? CerbosException.InvalidRequest)
                ?.reason
        }
    }
}

/** Owns the app's single [CerbosEmbeddedPDP] and the demo screen state. */
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

    /** Set when the last settings could not be saved (for example a Keystore failure). */
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

    /** Creates the PDP on first use; afterwards reconfigures it, which reuses the engine. */
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
            // The settings sheet rejects invalid URLs; fall back to the default Hub just in case.
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

    /** Called on resume: Android may have killed the engine while the app was in the background. */
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
