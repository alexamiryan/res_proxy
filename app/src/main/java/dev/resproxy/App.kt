package dev.resproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.resproxy.data.AccountRepository
import dev.resproxy.data.AppSettings
import dev.resproxy.data.BackupPayload
import dev.resproxy.data.ChainRepository
import dev.resproxy.data.Profile
import dev.resproxy.data.SecretStore
import dev.resproxy.data.SettingsRepository
import dev.resproxy.data.StatsRepository
import dev.resproxy.data.Provider
import dev.resproxy.provider.ProviderLocations
import dev.resproxy.reputation.ReputationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private val Context.dataStore by preferencesDataStore("resproxy")

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secrets = SecretStore()
    val accounts = AccountRepository(context.dataStore, secrets)
    val settings = SettingsRepository(context.dataStore, secrets)
    val stats = StatsRepository(context.dataStore)
    val chain = ChainRepository(context.dataStore, secrets)
    val reputation = ReputationRepository(settings)
    private val locationCatalogs = mapOf(
        Provider.IPROYAL to ProviderLocations(Provider.IPROYAL, File(context.filesDir, "iproyal_locations.json")),
        Provider.GEONODE to ProviderLocations(Provider.GEONODE, File(context.filesDir, "geonode_locations.json")),
    )

    fun locations(provider: Provider): ProviderLocations = locationCatalogs.getValue(provider)
    /** Completes once stored data has been migrated to the current schema. */
    val ready = CompletableDeferred<Unit>()

    /** Snapshot for export; connection state is left out so the target starts disconnected. */
    suspend fun exportBackup() = BackupPayload(
        createdAt = System.currentTimeMillis(),
        settings = settings.current().copy(wasConnected = false, sessionId = ""),
        accounts = accounts.accounts.first().values.toList(),
        chain = chain.get(),
        apiKeys = settings.currentKeys(),
    )

    /**
     * Replaces accounts, settings, API keys and the VPN chain with [b]. Runs in [appScope] so it
     * completes even if the calling screen goes away (saving accounts swaps the first-run UI out).
     * Accounts are written last for the same reason.
     */
    suspend fun importBackup(b: BackupPayload) = appScope.async {
        chain.save(b.chain)
        settings.setKeys(b.apiKeys)
        settings.update { b.settings.copy(wasConnected = false, sessionId = "", schema = AppSettings.SCHEMA) }
        accounts.replaceAll(b.accounts)
        b.accounts.forEach { acc -> locationCatalogs[acc.provider]?.let { appScope.launch { it.refresh(acc, force = true) } } }
    }.await()

    /** Connection spec for the selected provider, or null when no account is set up. */
    suspend fun connectionProfile(sessionId: String? = null): Profile? {
        val s = settings.current()
        val account = accounts.get(s.provider)?.takeIf { it.username.isNotBlank() } ?: return null
        return Profile.from(account, s, sessionId ?: s.sessionId)
    }
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            try {
                container.settings.migrate(container.accounts.migrateLegacyProfiles())
            } finally {
                container.ready.complete(Unit)
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_VPN, "VPN status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "IP reputation alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_VPN = "vpn"
        const val CHANNEL_ALERTS = "alerts"
    }
}

val Context.container: AppContainer get() = (applicationContext as App).container
