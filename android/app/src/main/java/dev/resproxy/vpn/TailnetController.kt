package dev.resproxy.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dev.resproxy.data.DiagLog
import dev.resproxy.data.SettingsRepository
import dev.resproxy.data.TailnetRepository
import dev.resproxy.data.TailnetSettings
import dev.resproxy.net.Tailnet
import dev.resproxy.net.TailnetRoute
import dev.resproxy.net.TailnetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What the UI shows about the tailnet. */
data class TailnetUi(
    val enabled: Boolean = false,
    /** The node runs (it may still need a login). */
    val running: Boolean = false,
    val status: TailnetStatus? = null,
    val error: String? = null,
)

/**
 * Runs this install's tailnet node while the tailnet is turned on, whether the proxy is on or off (the service runs
 * whenever either is on). Its lifecycle is separate from the proxy tunnel's: repairs, reconnects, rotations, switching
 * the proxy on or off and the kill switch never touch it, and a tailnet problem never fails the tunnel. The service
 * [bind]s it on create and [unbindNow]s it on destroy.
 */
class TailnetController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: TailnetRepository,
) {
    private val _ui = MutableStateFlow(TailnetUi())
    val ui: StateFlow<TailnetUi> = _ui.asStateFlow()

    private val lock = Mutex()
    private var job: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var enabled = false

    val dir: File get() = File(context.filesDir, "tailnet")
    /**
     * Exists while a node is starting (and its first minute). A Go panic inside tsnet takes the whole process down, VPN
     * included; if the process died with this file present, the next start leaves the tailnet off instead of crashing
     * again and again (on stays on for the tunnel).
     */
    private val startingMarker: File get() = File(context.filesDir, "tailnet_starting")
    private var settleJob: Job? = null
    private var crashChecked = false

    /** For the relays: the tailnet route while the tailnet is on (fail-fast while it isn't running), else null. */
    fun route(): TailnetRoute? = when {
        !enabled -> null
        Tailnet.running -> Tailnet
        else -> Tailnet.Down
    }

    /** What the node is (re)started from: a change restarts it. */
    private data class Spec(val s: TailnetSettings, val authKey: String)

    @OptIn(FlowPreview::class)
    fun bind(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { pollStatus() }
            // Debounced: text fields save as you type, and every change of the spec restarts the node.
            combine(settings.settings, secrets.current) { s, k -> Spec(s.tailnet, k.authKey) }
                .distinctUntilChanged()
                .debounce(1500)
                .collect { spec ->
                    // A tailnet problem must never reach the service's crash handler (which reconnects the tunnel).
                    try {
                        apply(spec)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagLog.log("tailnet: couldn't start", e)
                        _ui.update { it.copy(running = Tailnet.running, error = e.message ?: e.javaClass.simpleName) }
                    }
                }
        }
    }

    /** The service is being destroyed (no suspending there). */
    fun unbindNow() {
        job?.cancel()
        job = null
        enabled = false
        stopLocked()
        _ui.update { it.copy(running = false, status = null) }
    }

    private suspend fun apply(spec: Spec) = lock.withLock {
        val run = spec.s.enabled
        enabled = run
        stopLocked()
        _ui.update { TailnetUi(enabled = spec.s.enabled) }
        if (!run) {
            // Turned off: a marker left from earlier is stale, and turning it on again should just try.
            if (!spec.s.enabled) { startingMarker.delete(); crashChecked = true }
            return@withLock
        }
        if (!Tailnet.available) {
            _ui.update { it.copy(error = "This build has no tailnet support") }
            return@withLock
        }
        if (!crashChecked) {
            crashChecked = true
            if (startingMarker.exists() && lastExitWasCrash()) {
                startingMarker.delete()
                DiagLog.log("tailnet: the app stopped while the tailnet was starting last time; leaving it off")
                _ui.update { it.copy(error = "The tailnet stopped the app when it last started, so it stays off. Turn it off and on to try again.") }
                return@withLock
            }
        }
        registerNetworkCallback()
        Tailnet.reportInterfaces(TUN_ADDRESSES)
        val host = spec.s.hostname.trim().ifEmpty { defaultHostname() }
        runCatching { startingMarker.writeText(System.currentTimeMillis().toString()) }
        val err = Tailnet.start(
            dir = dir.absolutePath,
            hostname = host,
            authKey = spec.authKey,
            controlUrl = spec.s.controlUrl.trim(),
            stateKeyHex = secrets.stateKey(),
            localAddr = if (spec.s.acceptInbound) HevConfig.TUN_V4 else "",
            loopbackPorts = spec.s.loopbackPorts,
        )
        if (err != null) {
            startingMarker.delete()
            DiagLog.log("tailnet: start failed: $err")
            _ui.update { it.copy(error = err) }
        } else {
            settleJob?.cancel()
            settleJob = job?.let { j -> CoroutineScope(j).launch { delay(SETTLE_MS); startingMarker.delete() } }
            DiagLog.log("tailnet: started as $host (" + (spec.s.controlUrl.trim().ifEmpty { "Tailscale" }) + ")")
            _ui.update { it.copy(running = true, error = null) }
        }
    }

    private fun stopLocked() {
        settleJob?.cancel()
        settleJob = null
        if (Tailnet.running) {
            // Only a node that ran clears the marker: one left by a crashed process must survive until the check.
            startingMarker.delete()
            Tailnet.stop()
            DiagLog.log("tailnet: stopped")
        }
        unregisterNetworkCallback()
    }

    /**
     * Removes this device from the tailnet (when the node is running, so the control server hears it), turns the tailnet
     * off and forgets the node and the auth key. Without a running node, only the local state goes: the device stays
     * listed in the admin console until removed there.
     */
    suspend fun logout(): String? {
        val err = lock.withLock {
            val e = if (Tailnet.running) Tailnet.logout() else null
            stopLocked()
            e
        }
        settings.update { it.copy(tailnet = it.tailnet.copy(enabled = false)) }
        lock.withLock {
            dir.deleteRecursively()
            secrets.clear()
            _ui.update { TailnetUi() }
        }
        DiagLog.log("tailnet: logged out" + (err?.let { " ($it)" } ?: ""))
        return err
    }

    /** The previous process ended in a crash (Java or native), not e.g. a low-memory kill or an update. */
    private fun lastExitWasCrash(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return true
        val last = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull() ?: return true
        return last.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
            last.reason == android.app.ApplicationExitInfo.REASON_CRASH
    }

    private suspend fun pollStatus() {
        var last: String? = null
        var summaryAt = System.currentTimeMillis()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val st = if (Tailnet.running) runCatching { Tailnet.status() }.getOrNull() else null
            if (st != null) _ui.update { it.copy(status = st, running = true) }
            val word = st?.let { s -> s.state + (s.ips.firstOrNull()?.let { " $it" } ?: "") }
            if (word != null && word != last) {
                DiagLog.log("tailnet: $word, ${st.peers.size} device(s)" + (st.error.takeIf { it.isNotEmpty() }?.let { "; $it" } ?: ""))
                last = word
            }
            val now = System.currentTimeMillis()
            if (st != null && now - summaryAt > SUMMARY_MS) {
                summaryAt = now
                DiagLog.log("tailnet: ${st.state}, ${st.peers.count { it.online }}/${st.peers.size} devices online, " +
                    "connections in ${st.inboundConns} out ${st.outboundConns}")
            }
            delay(if (st?.running == true) 5_000 else 1_500)
        }
    }

    /** tsnet's own network monitor polls slowly on Android; tell it about switches right away. */
    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                Tailnet.reportInterfaces(TUN_ADDRESSES)
                Tailnet.networkChanged()
            }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed()
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { netCallback = cb }
            .onFailure { DiagLog.log("tailnet: network callback", it) }
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null
    }

    companion object {
        private const val SUMMARY_MS = 15 * 60_000L
        /** A node that ran this long without taking the app down is considered fine. */
        private const val SETTLE_MS = 60_000L
        private val TUN_ADDRESSES = setOf(HevConfig.TUN_V4, HevConfig.TUN_V6)

        /** `resproxy-<model>`, plus the Android user on secondary users, so every profile is its own device. */
        fun defaultHostname(model: String = Build.MODEL ?: "android", userId: Int = android.os.Process.myUserHandle().hashCode()): String {
            val base = "resproxy-" + model.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "android" }
            return (if (userId != 0) "$base-u$userId" else base).take(63).trimEnd('-')
        }
    }
}
