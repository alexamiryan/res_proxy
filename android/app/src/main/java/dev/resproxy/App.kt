package dev.resproxy

import dev.resproxy.vpn.KeepAliveJob
import dev.resproxy.data.DiagLog
import android.os.Build
import android.app.ActivityManager
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
    val latency = dev.resproxy.net.LatencyProbe()
    val reputation = ReputationRepository(settings)
    private val locationCatalogs = mapOf(
        Provider.IPROYAL to ProviderLocations(Provider.IPROYAL, File(context.filesDir, "iproyal_locations.json")),
        Provider.GEONODE to ProviderLocations(Provider.GEONODE, File(context.filesDir, "geonode_locations.json")),
    )

    fun locations(provider: Provider): ProviderLocations = locationCatalogs.getValue(provider)
    /** Completes once stored data has been migrated to the current schema. */
    val ready = CompletableDeferred<Unit>()

    /**
     * Snapshot for export; connection state is left out so the target starts disconnected. [shareMullvadDevice]
     * includes this install's Mullvad device so the importing side reuses it (see [BackupPayload.mullvadDevice]).
     */
    suspend fun exportBackup(shareMullvadDevice: Boolean = false) = BackupPayload(
        createdAt = System.currentTimeMillis(),
        settings = settings.current().copy(wasConnected = false, sessionId = ""),
        accounts = accounts.accounts.first().values.toList(),
        apiKeys = settings.currentKeys(),
        vpnProfiles = vpn.profiles(),
        mullvadNumber = vpn.mullvad()?.number.orEmpty(),
        mullvadDevice = if (shareMullvadDevice) vpn.mullvad()?.device else null,
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
        b.mullvadDevice?.let { adoptMullvadDevice(b.mullvadNumber, it) }
        settings.setKeys(b.apiKeys)
        settings.update {
            val exit = if (legacy != null && b.settings.vpnExit.isEmpty()) VpnExit.imported(legacy.id) else b.settings.vpnExit
            b.settings.copy(wasConnected = false, sessionId = "", vpnExit = exit)
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

    /**
     * Uses a Mullvad device shared from another install. This install's own device, if it had another one, is removed
     * from the account so its slot is freed.
     */
    suspend fun adoptMullvadDevice(number: String, device: dev.resproxy.data.MullvadDevice) {
        val n = MullvadClient.normalize(number)
        if (n.isEmpty() || device.privateKey.isBlank()) return
        val cur = vpn.mullvad()?.takeIf { it.number == n }
        cur?.device?.takeIf { it.id != device.id }?.let { old -> runCatching { mullvad.removeDevice(n, old.id) } }
        vpn.saveMullvad(dev.resproxy.data.MullvadAccount(number = n, expiry = cur?.expiry.orEmpty(), device = device))
    }

    /**
     * Checks that this install's Mullvad device still exists. If it was removed (on mullvad.net or by another install)
     * the install is marked signed out. Returns false when removed, null when there's nothing to check or no answer.
     */
    suspend fun verifyMullvadDevice(): Boolean? {
        val acc = vpn.mullvad() ?: return null
        val d = acc.device ?: return null
        val ok = runCatching { mullvad.isDeviceRegistered(acc.number, d.id) }.getOrNull() ?: return null
        if (!ok) vpn.saveMullvad(acc.copy(device = null, signedOut = true))
        return ok
    }

    /** Signs out on this install only: forgets its device key; the device stays on the account for anyone sharing it. */
    suspend fun signOutMullvadHere() {
        vpn.mullvad()?.let { vpn.saveMullvad(it.copy(device = null, signedOut = true)) }
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
                val cur = vpn.mullvad()
                if (cur != null && cur.device == null && cur.signedOut) {
                    throw IOException("Signed out of Mullvad on this phone profile. Log in again in Settings → VPN.")
                }
                val acc = cur?.takeIf { it.device != null } ?: mullvadLogin()
                val relays = runCatching { mullvad.refreshRelays() }.getOrElse { err -> mullvad.relays.value.ifEmpty { throw err } }
                val relay = MullvadClient.pick(e.code, relays, vpnSlot()) ?: throw IOException("No Mullvad server matches “${e.code}”")
                "Mullvad ${relay.city} · ${relay.hostname}" to WireGuardConfig.parse(MullvadClient.wgQuick(acc.device!!, relay))
            }
            null -> throw IOException("Choose a VPN server on the home screen")
        }
    }

    // Which of a location's Mullvad servers this install uses: different per phone and per Android user (ANDROID_ID),
    // moved on when another install with the same Mullvad device turns out to be on the same server.
    private val vpnSlotFile = File(context.filesDir, "vpn_slot")
    private val vpnSeed = runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty().hashCode()
    @Volatile private var vpnShift = runCatching { vpnSlotFile.readText().trim().toInt() }.getOrDefault(0)

    fun vpnSlot(): Int = Math.floorMod(vpnSeed, 1 shl 20) + vpnShift

    /** Moves this install to another server of the selected Mullvad location; false when there is no other one. */
    suspend fun nextVpnServer(): Boolean {
        val s = settings.current()
        val e = VpnExit.parse(s.vpnExit) as? VpnExit.Mullvad ?: return false
        if (!s.chainEnabled) return false
        val n = MullvadClient.candidates(e.code, mullvad.relays.value).size
        if (n < 2) return false
        vpnShift = Math.floorMod(vpnShift + 1 + (Math.random() * (n - 1)).toInt(), 1 shl 20)
        runCatching { vpnSlotFile.writeText(vpnShift.toString()) }
        return true
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
        DiagLog.init(filesDir)
        logProcessStart()
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
        KeepAliveJob.schedule(this)
    }

    private fun exitReason(r: Int) = when (r) {
        1 -> "exited by itself"; 2 -> "killed (signal)"; 3 -> "killed for low memory"; 4 -> "crashed"; 5 -> "native crash"
        6 -> "not responding (ANR)"; 7 -> "failed to start"; 8 -> "permission change"; 9 -> "excessive resource use"
        10 -> "user request"; 11 -> "user (profile) stopped"; 12 -> "dependency died"; 13 -> "other"; 14 -> "frozen"
        15 -> "package state change"; 16 -> "app updated"
        else -> "reason $r"
    }

    /** Why the previous process ended (killed for memory, crashed, user stopped, updated…): the first question after a drop. */
    private fun logProcessStart() {
        val last = if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
            }.getOrNull()
        } else null
        DiagLog.log(
            "process started" + (last?.let {
                " — previous one ended ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(it.timestamp))}: " +
                    exitReason(it.reason) + (it.description?.takeIf { d -> d.isNotBlank() }?.let { d -> " ($d)" } ?: "")
            } ?: ""),
        )
    }

    companion object {
        const val CHANNEL_VPN = "vpn"
        const val CHANNEL_ALERTS = "alerts"
    }
}

val Context.container: AppContainer get() = (applicationContext as App).container
