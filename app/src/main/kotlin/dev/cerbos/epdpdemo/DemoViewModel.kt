package dev.cerbos.epdpdemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cerbos.epdp.CerbosEmbeddedPDP
import dev.cerbos.epdp.CerbosException
import dev.cerbos.epdp.CheckResult
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DemoUiState(
    val settings: HubSettings = HubSettings(),
    /** `null` until the saved settings have loaded and the PDP has been created. */
    val pdp: CerbosEmbeddedPDP.State? = null,
    val scenario: DemoScenario = DemoScenario(),
    val check: CheckUiState = CheckUiState(),
    /** Set when the last settings could not be saved (for example a Keystore failure). */
    val settingsError: String? = null,
)

data class CheckUiState(
    val isChecking: Boolean = false,
    val result: CheckResult? = null,
    val duration: Duration? = null,
    val error: String? = null,
)

/** Owns the app's single [CerbosEmbeddedPDP] and the demo screen state. */
class DemoViewModel(
    private val settingsRepository: HubSettingsRepository,
    private val createPdp: (CerbosEmbeddedPDP.Configuration) -> CerbosEmbeddedPDP,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DemoUiState())
    val uiState: StateFlow<DemoUiState> = _uiState.asStateFlow()

    private var pdp: CerbosEmbeddedPDP? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.load()
            _uiState.update { it.copy(settings = settings) }
            startPdp(settings)
        }
    }

    /** Saves the settings and restarts the PDP with them. */
    fun applySettings(settings: HubSettings) {
        _uiState.update { it.copy(settings = settings, settingsError = null) }
        viewModelScope.launch {
            try {
                settingsRepository.save(settings)
            } catch (error: GeneralSecurityException) {
                reportSettingsError(error)
            } catch (error: ProviderException) {
                reportSettingsError(error)
            }
            startPdp(settings)
        }
    }

    private fun reportSettingsError(error: Exception) {
        _uiState.update {
            it.copy(settingsError = "The client secret was not saved: ${error.message ?: error}")
        }
    }

    /** Creates the PDP on first use; afterwards reconfigures it, which reuses the engine. */
    private suspend fun startPdp(settings: HubSettings) {
        _uiState.update { it.copy(check = CheckUiState()) }
        val configuration = settings.toConfiguration()
        val existing = pdp
        if (existing != null) {
            existing.reconfigure(configuration)
            return
        }
        val created = createPdp(configuration)
        pdp = created
        viewModelScope.launch {
            created.state.collect { s -> _uiState.update { it.copy(pdp = s) } }
        }
        created.start()
    }

    fun updateScenario(scenario: DemoScenario) {
        _uiState.update { it.copy(scenario = scenario) }
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
        if (_uiState.value.check.isChecking) return
        val request = _uiState.value.scenario.request()
        _uiState.update { it.copy(check = it.check.copy(isChecking = true, error = null)) }
        viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            val check =
                try {
                    val response = pdp.checkResources(request)
                    CheckUiState(
                        result = response.result(DemoScenario.RESOURCE_KIND, "1"),
                        duration = started.elapsedNow(),
                    )
                } catch (error: CerbosException) {
                    _uiState.value.check.copy(isChecking = false, error = error.message)
                }
            _uiState.update { it.copy(check = check) }
        }
    }

    override fun onCleared() {
        pdp?.close()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY])
                DemoViewModel(HubSettingsRepository(application)) {
                    CerbosEmbeddedPDP(application, it)
                }
            }
        }
    }
}
