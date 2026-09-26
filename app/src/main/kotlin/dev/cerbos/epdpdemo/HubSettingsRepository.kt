package dev.cerbos.epdpdemo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cerbos.epdp.CerbosEmbeddedPDP
import dev.cerbos.epdp.CerbosException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Hub connection settings from the settings sheet. */
data class HubSettings(
    val ruleId: String = DEFAULT_RULE_ID,
    val hubBaseUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
) {
    fun toConfiguration(): CerbosEmbeddedPDP.Configuration {
        val url = hubBaseUrl.trim()
        val id = clientId.trim()
        return CerbosEmbeddedPDP.Configuration(
            ruleId = ruleId.trim(),
            // The settings sheet rejects invalid URLs; fall back to the default Hub just in case.
            hubBaseUrl = url.takeIf { it.isNotEmpty() && hubUrlProblem(it) == null },
            credentials =
                if (id.isNotEmpty() && clientSecret.isNotEmpty()) {
                    CerbosEmbeddedPDP.HubCredentials(id, clientSecret)
                } else {
                    null
                },
        )
    }

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

private val Context.hubSettingsStore: DataStore<Preferences> by
    preferencesDataStore(name = "hub_settings")

/** Persists [HubSettings] in DataStore. The client secret is stored encrypted by [cipher]. */
class HubSettingsRepository(
    private val store: DataStore<Preferences>,
    private val cipher: KeystoreCipher = KeystoreCipher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.hubSettingsStore)

    suspend fun load(): HubSettings =
        withContext(ioDispatcher) {
            val preferences = store.data.first()
            HubSettings(
                ruleId = preferences[RULE_ID] ?: HubSettings.DEFAULT_RULE_ID,
                hubBaseUrl = preferences[HUB_BASE_URL].orEmpty(),
                clientId = preferences[CLIENT_ID].orEmpty(),
                clientSecret = preferences[CLIENT_SECRET]?.let(cipher::decrypt).orEmpty(),
            )
        }

    /** Throws [java.security.GeneralSecurityException] if the secret can't be encrypted. */
    suspend fun save(settings: HubSettings) {
        withContext(ioDispatcher) {
            val secret = settings.clientSecret.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
            store.edit {
                it[RULE_ID] = settings.ruleId
                it[HUB_BASE_URL] = settings.hubBaseUrl
                it[CLIENT_ID] = settings.clientId
                if (secret != null) it[CLIENT_SECRET] = secret else it.remove(CLIENT_SECRET)
            }
        }
    }

    private companion object {
        val RULE_ID = stringPreferencesKey("ruleId")
        val HUB_BASE_URL = stringPreferencesKey("hubBaseUrl")
        val CLIENT_ID = stringPreferencesKey("clientId")
        val CLIENT_SECRET = stringPreferencesKey("clientSecret")
    }
}
