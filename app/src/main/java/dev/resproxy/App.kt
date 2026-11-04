package dev.resproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.resproxy.data.AccountRepository
import dev.resproxy.data.AppSettings
import dev.resproxy.data.BackupPayload
import dev.resproxy.data.Profile
import dev.resproxy.data.SecretStore
import dev.resproxy.data.SettingsRepository
import dev.resproxy.data.StatsRepository
import dev.resproxy.data.Provider
import dev.resproxy.data.VpnExit
import dev.resproxy.data.VpnProfile
import dev.resproxy.data.VpnRepository
import dev.resproxy.net.MullvadClient
import dev.resproxy.net.WireGuardConfig
import java.io.IOException
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
    val vpn = VpnRepository(context.dataStore, secrets)
    val mullvad = MullvadClient(File(context.filesDir, "mullvad_relays.json"))
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
        apiKeys = settings.currentKeys(),
        vpnProfiles = vpn.profiles(),
        mullvadNumber = vpn.mullvad()?.number.orEmpty(),
    )

    /**
     * Replaces accounts, settings, API keys and the VPN chain with [b]. Runs in [appScope] so it
     * completes even if the calling screen goes away (saving accounts swaps the first-run UI out).
     * Accounts are written last for the same reason.
     */
    suspend fun importBackup(b: BackupPayload) = appScope.async {
        // Backups from before VPN profiles carry a single `chain`.
        val legacy = b.chain?.takeIf { b.vpnProfiles.isEmpty() && it.config.isNotBlank() }?.let { VpnProfile(name = it.name, config = it.config) }
        vpn.saveProfiles(b.vpnProfiles.ifEmpty { listOfNotNull(legacy) })
        setMullvadNumber(b.mullvadNumber)
        settings.setKeys(b.apiKeys)
        settings.update {
            val exit = if (legacy != null && b.settings.vpnExit.isEmpty()) VpnExit.imported(legacy.id) else b.settings.vpnExit
            b.settings.copy(wasConnected = false, sessionId = "", schema = AppSettings.SCHEMA, vpnExit = exit)
        }
        accounts.replaceAll(b.accounts)
        b.accounts.forEach { acc -> locationCatalogs[acc.provider]?.let { appScope.launch { it.refresh(acc, force = true) } } }
    }.await()

    /** Keeps this install's Mullvad device when the number is unchanged; a new number logs in again on connect. */
    suspend fun setMullvadNumber(number: String) {
        val n = MullvadClient.normalize(number)
        val cur = vpn.mullvad()
        when {
            n.isEmpty() -> vpn.saveMullvad(null)
            cur?.number != n -> vpn.saveMullvad(dev.resproxy.data.MullvadAccount(number = n))
        }
    }

    /** Logs in to Mullvad (registering this install's key if needed) and stores the result. */
    suspend fun mullvadLogin(): dev.resproxy.data.MullvadAccount {
        val cur = vpn.mullvad() ?: throw IOException("Add your Mullvad account number in Settings → VPN")
        val acc = mullvad.login(cur.number, cur.device)
        vpn.saveMullvad(acc)
        return acc
    }

    /**
     * The WireGuard VPN to chain through for [s], or null when chaining is off.
     * Returns a display name and the parsed config; throws with a user-facing message.
     */
    suspend fun resolveVpn(s: AppSettings): Pair<String, WireGuardConfig>? {
        if (!s.chainEnabled) return null
        return when (val e = VpnExit.parse(s.vpnExit)) {
            is VpnExit.Imported -> {
                val p = vpn.profiles().firstOrNull { it.id == e.id } ?: throw IOException("The selected VPN profile was removed")
                p.name to WireGuardConfig.parse(p.config)
            }
            is VpnExit.Mullvad -> {
                val acc = vpn.mullvad()?.takeIf { it.device != null } ?: mullvadLogin()
                val relays = runCatching { mullvad.refreshRelays() }.getOrElse { err -> mullvad.relays.value.ifEmpty { throw err } }
                val relay = MullvadClient.pick(e.code, relays) ?: throw IOException("No Mullvad server matches “${e.code}”")
                "Mullvad ${relay.city} · ${relay.hostname}" to WireGuardConfig.parse(MullvadClient.wgQuick(acc.device!!, relay))
            }
            null -> throw IOException("Choose a VPN server on the home screen")
        }
    }

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
                // Leftovers of a removed profile-sync experiment (Android blocks cross-profile traffic).
                dataStore.edit { p -> listOf("sync_code", "sync_stamp", "sync_known").forEach { p.remove(stringPreferencesKey(it)) } }
                container.vpn.migrateLegacyChain()?.let { id ->
                    container.settings.update { if (it.vpnExit.isEmpty()) it.copy(vpnExit = VpnExit.imported(id)) else it }
                }
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
