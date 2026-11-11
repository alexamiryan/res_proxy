package dev.resproxy.vpn

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.resproxy.App
import dev.resproxy.MainActivity
import dev.resproxy.R
import dev.resproxy.data.DiagLog
import dev.resproxy.data.Profile
import dev.resproxy.data.SplitMode
import dev.resproxy.data.TunnelMode
import dev.resproxy.data.Verdict
import dev.resproxy.data.VpnExit
import dev.resproxy.net.LocalHttpProxy
import dev.resproxy.net.LocalSocksServer
import dev.resproxy.net.ProxyAuthException
import dev.resproxy.net.ProxyProbe
import dev.resproxy.net.RelayHealth
import dev.resproxy.net.exitNodeDead
import dev.resproxy.net.TrafficGate
import dev.resproxy.net.Upstream
import dev.resproxy.net.WireGuard
import dev.resproxy.net.WireGuardConfig
import dev.resproxy.provider.Presets
import dev.resproxy.ui.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

/**
 * Owns the TUN interface and the upstream plumbing:
 * apps → TUN → hev-socks5-tunnel → [LocalSocksServer] relay → (WireGuard →) SOCKS5 / HTTP proxy.
 *
 * The TUN is established before anything touches the network and stays up across rotations,
 * location changes, settings changes, repairs and outages, so with the kill switch on traffic is
 * held/dropped, never sent direct.
 */
class ProxyVpnService : VpnService() {

    private val container by lazy { (application as App).container }
    /**
     * A bug in any background job must never kill the process: that drops the VPN with nobody left to bring
     * it back. Log it and reconnect instead.
     */
    private val crashHandler = CoroutineExceptionHandler { _, e ->
        DiagLog.log("background job failed, reconnecting", e)
        scope.launch {
            delay(3000)
            if (container.settings.current().wasConnected) connect("after an error")
        }
    }
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashHandler)
    private val lock = Mutex()

    private var tun: ParcelFileDescriptor? = null
    private var upstream: Upstream? = null
    /** Local proxy for the app's own checks: never gated. */
    private var httpProxy: LocalHttpProxy? = null
    /** Proxy-only mode: the system HTTP proxy apps use (gated). */
    private var systemProxy: LocalHttpProxy? = null
    private var socksBridge: LocalSocksServer? = null
    private var hevRunning = false
    private var profile: Profile? = null
    /** Set when the upstream proxy is reached through a WireGuard VPN. */
    private var chainConfig: WireGuardConfig? = null
    private var workers: List<Job> = emptyList()
    private var checkJob: Job? = null

    @Volatile private var killSwitch = true
    /** The user turned the tunnel on and hasn't turned it off (`wasConnected`): keep it running. */
    @Volatile private var wantConnected = false
    /** Disconnect / revoke in progress: don't restart. */
    @Volatile private var userStopped = false
    private var reconnectFailures = 0
    @Volatile private var nextAttemptAt = 0L
    /** Set by [kick]: the health loop probes right away. */
    @Volatile private var kicked = false
    private var unlockReceiver: BroadcastReceiver? = null
    private var lastStartAt = 0L
    @Volatile private var bypassLan = true
    /** Open only while the current exit IP passed the reputation check: apps never use an unchecked or bad IP. */
    private val gate = TrafficGate()
    /** Exit IP that passed the check and that the gate was opened for. */
    @Volatile private var cleanIp: String? = null
    /** Blocked with no clean IP found (bad IP, rotations used up): wait for "New IP" instead of re-checking. */
    @Volatile private var waitingForUser = false
    private var lastCheckKick = 0L
    private val relayHealth = RelayHealth()
    /** EWMA of proxy handshake round trips (ms). */
    @Volatile private var proxyRtt: Double? = null
    private var chainStartedAt = 0L
    private var lastRepairAt = 0L
    private var failedRepairs = 0
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** What the running tunnel was built from; a change while connected rebuilds the affected part. */
    private data class Keys(val tun: String, val upstream: String)
    private var builtWith: Keys? = null

    private val configFile by lazy { File(filesDir, "hev.yml") }

    override fun onCreate() {
        super.onCreate()
        alive = true
        log("service created")
        scope.launch {
            container.settings.settings.collect { killSwitch = it.killSwitch; bypassLan = it.bypassLan; wantConnected = it.wasConnected }
        }
        scope.launch { watchSettings() }
        scope.launch { watchApiKeys() }
        scope.launch { supervise() }
        // Every phase change and its reason goes to the diagnostics log.
        scope.launch {
            TunnelState.status.map { it.phase to it.message }.distinctUntilChanged().collect { (phase, msg) ->
                DiagLog.log("status: $phase" + (msg?.let { " — $it" } ?: ""))
            }
        }
        // Unlocking the phone: retry right away instead of waiting for the next backoff step.
        val unlock = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { kick("phone unlocked") }
        }
        runCatching { ContextCompat.registerReceiver(this, unlock, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { unlockReceiver = unlock }
        KeepAliveJob.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(buildNotification())
        val active = TunnelState.status.value.isActive
        log("start command: ${intent?.action ?: "sticky restart"}")
        // A command for a tunnel that isn't running: stop again, unless the tunnel should be on (the supervisor
        // brings it back; stopping here would destroy the service mid-reconnect).
        fun idle() { if (!wantConnected) stopService() }
        when (intent?.action) {
            ACTION_DISCONNECT -> scope.launch {
                userStopped = true
                wantConnected = false
                container.settings.update { it.copy(wasConnected = false) }
                log("disconnected by the user")
                DeadManAlarm.disarm(this@ProxyVpnService)
                lock.withLock { teardown() }
                stopService()
            }
            ACTION_ROTATE -> if (!active) idle() else scope.launch {
                switchSession(Presets.newSessionId())
                launchCheck(force = true)
            }
            // Provider / location change: reuse that place's remembered session (same IP) if it's still alive.
            ACTION_APPLY_LOCATION -> if (!active) idle() else scope.launch {
                switchSession(container.settings.current().sessionId.ifEmpty { Presets.newSessionId() })
                launchCheck(force = false)
            }
            ACTION_RECHECK -> if (!active) idle() else launchCheck(force = true)
            // ACTION_CONNECT, always-on start (SERVICE_INTERFACE) and sticky restarts (null intent)
            else -> {
                userStopped = false
                // Always-on, the update receiver and the watchdog can all start us at once: connect once.
                val now = System.currentTimeMillis()
                if (now - lastStartAt < 5_000) log("duplicate start ignored")
                else {
                    lastStartAt = now
                    scope.launch { connect(intent?.action?.substringAfterLast('.') ?: "sticky restart") }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Android took the VPN away. If our VPN consent is gone (another VPN app connected, or the user disconnected or
     * forgot the VPN in system settings), stop for good. Otherwise it was the system (e.g. a network stack restart):
     * reconnect.
     */
    override fun onRevoke() {
        scope.launch {
            val consent = VpnService.prepare(this@ProxyVpnService) == null
            log("VPN revoked by Android (permission ${if (consent) "still granted, reconnecting" else "gone, stopping"})")
            lock.withLock { teardown() }
            if (consent) {
                TunnelState.update { it.copy(phase = Phase.PROXY_DOWN, message = "Android stopped the VPN — reconnecting") }
                kick("revoked")
            } else {
                userStopped = true
                wantConnected = false
                container.settings.update { it.copy(wasConnected = false) }
                TunnelState.update { TunnelStatus(phase = Phase.ERROR, message = "Another VPN took over, or the VPN was turned off in Android settings") }
                stopService()
            }
        }
    }

    override fun onDestroy() {
        val restart = wantConnected && !userStopped
        log("service destroyed" + if (restart) " while it should be on — restarting soon" else "")
        if (restart) KeepAliveJob.scheduleSoon(this) else DeadManAlarm.disarm(this)
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        teardown()
        scope.cancel()
        alive = false
        super.onDestroy()
    }

    private fun log(message: String, e: Throwable? = null) = DiagLog.log(message, e)

    /** Retry now: reset the reconnect backoff and make the health loop probe on its next tick. */
    private fun kick(why: String) {
        if (!wantConnected) return
        log("kick: $why")
        nextAttemptAt = 0
        kicked = true
    }

    /**
     * The one place that guarantees "on stays on": whenever the user wants the tunnel and nothing is running it
     * (a failed or aborted connect, a teardown, a bug), connect again with backoff. A tunnel that is up but can't
     * reach the proxy (PROXY_DOWN with workers running) is left to the health loop.
     */
    private suspend fun supervise() {
        while (scope.isActive) {
            delay(SUPERVISE_TICK_MS)
            if (wantConnected && !userStopped) DeadManAlarm.arm(this) else DeadManAlarm.disarm(this)
            val plumbingUp = profile != null && workers.isNotEmpty() && workers.all { it.isActive }
            if (plumbingUp) {
                if (TunnelState.status.value.phase == Phase.CONNECTED) reconnectFailures = 0
                continue
            }
            val now = System.currentTimeMillis()
            if (!KeepAlive.shouldReconnect(wantConnected && !userStopped, plumbingUp, lock.isLocked, now, nextAttemptAt)) continue
            reconnectFailures++
            nextAttemptAt = now + KeepAlive.backoffMs(reconnectFailures)
            connect("keep-alive: tunnel wasn't running, attempt $reconnectFailures")
        }
    }

    // region lifecycle

    private suspend fun keys(): Keys {
        val s = container.settings.current()
        val cs = s.connection
        val acc = container.accounts.get(s.provider)
        val vpn = if (!s.chainEnabled) "" else when (val e = VpnExit.parse(s.vpnExit)) {
            is VpnExit.Imported -> container.vpn.profiles().firstOrNull { it.id == e.id }?.config.orEmpty().hashCode().toString()
            is VpnExit.Mullvad -> e.code + "/" + (container.vpn.mullvad()?.number ?: "")
            null -> ""
        }
        return Keys(
            tun = listOf(cs.tunnelMode, cs.splitMode, cs.apps.sorted(), s.bypassLan, lockdown()).toString(),
            upstream = listOf(
                s.provider, cs.protocol, cs.sticky, cs.lifetimeMinutes, cs.gateways, acc?.username, acc?.password,
                s.chainEnabled, s.vpnExit, vpn,
            ).toString(),
        )
    }

    /** Applies settings changes to a running tunnel (debounced, so typing in a field rebuilds once). */
    @OptIn(FlowPreview::class)
    private suspend fun watchSettings() {
        combine(container.settings.settings, container.accounts.accounts, container.vpn.profiles, container.vpn.mullvad) { _, _, _, _ -> }
            .debounce(1500)
            .collect {
                val built = builtWith ?: return@collect
                if (!TunnelState.status.value.isActive) return@collect
                val now = keys()
                when {
                    now.tun != built.tun -> {
                        log("routing settings changed, reconnecting")
                        connect("routing settings changed")
                    }
                    now.upstream != built.upstream -> {
                        log("connection settings changed, rebuilding upstream")
                        lock.withLock {
                            if (profile == null) return@withLock
                            builtWith = now
                            blockTraffic()
                            TunnelState.update { it.copy(phase = Phase.CONNECTING, exitIp = null, report = null, message = "Applying new settings…") }
                            if (reloadChain() && rebuild()) TunnelState.update { it.copy(phase = Phase.CONNECTED, message = null) }
                        }
                        launchCheck(force = false)
                    }
                }
            }
    }

    /** New or removed API keys change which sources a report has: re-check the current IP right away. */
    @OptIn(FlowPreview::class)
    private suspend fun watchApiKeys() {
        container.settings.apiKeys.distinctUntilChanged().drop(1).debounce(1000).collect {
            container.reputation.clearCache()
            val st = TunnelState.status.value
            if (st.phase == Phase.CONNECTED && !st.checking) {
                log("API keys changed, re-checking reputation")
                launchCheck(force = true)
            }
        }
    }

    /**
     * (Re)builds everything. Never call it from a worker ([workers], [checkJob]): its teardown cancels them, which
     * would abort this connect half way. Use [reconnectAsync] there.
     */
    private suspend fun connect(reason: String) {
        lock.withLock {
            log("connect ($reason)")
            // Keep the old TUN until the new one is established, so a reconnect never leaks traffic.
            val oldTun = tun
            tun = null
            try {
                connectLocked()
            } catch (e: CancellationException) {
                log("connect aborted")
                throw e
            } catch (e: Exception) {
                log("connect failed", e)
                stopKeepingTun()
                fail(describe(e))
            } finally {
                // The old TUN is closed only once a new one replaced it. Without a new one, the kill switch keeps the
                // old one: nothing reads it any more, so traffic stays blocked instead of going direct.
                when {
                    tun != null && tun !== oldTun -> runCatching { oldTun?.close() }
                    tun == null && killSwitch -> tun = oldTun
                    tun == null -> runCatching { oldTun?.close() }
                }
            }
        }
    }

    /** Failure while (re)connecting: stop everything; with the kill switch keep the TUN up so traffic stays blocked. */
    private fun stopKeepingTun() = if (killSwitch) teardownKeepingTun() else teardown()

    /** Runs [connect] in its own job, so callers inside workers aren't cancelled by its teardown. */
    private fun reconnectAsync(reason: String) {
        scope.launch { connect(reason) }
    }

    /** Builds the tunnel; [connect] closes the previous TUN once this established a new one. */
    private suspend fun connectLocked() {
        run {
            teardown()
            var s = container.settings.current()
            if (s.connection.sticky) {
                // Remember the session for this provider + location (new one if none), so switching back reuses it.
                container.settings.update { it.withSession(it.sessionId.ifEmpty { Presets.newSessionId() }) }
                s = container.settings.current()
            }
            val p = container.connectionProfile()
            if (p == null) {
                return fail("Add your ${s.provider.label} account first")
            }
            profile = p
            builtWith = keys()
            TunnelState.update {
                TunnelStatus(phase = Phase.CONNECTING, profileId = p.id, profileName = displayName(p, s.location.label), sessionId = p.sessionId)
            }
            wantConnected = true
            container.settings.update { it.copy(wasConnected = true) }
            val full = p.tunnelMode == TunnelMode.FULL
            // Full tunnel: capture all traffic of the routed apps before any upstream I/O.
            if (full) {
                val assumedLockdown = lockdown()
                tun = buildTun(p, s.bypassLan) ?: return fail("VPN permission not granted")
                // Android reports "Block connections without VPN" only once our VPN is up; the LAN routes depend on it.
                // Rebuild the TUN right away instead of a whole reconnect later (watchSettings would see the change).
                if (lockdown() != assumedLockdown) {
                    log("lockdown ${if (lockdown()) "on" else "off"}: rebuilding the TUN routes")
                    val first = tun
                    tun = buildTun(p, s.bypassLan) ?: return fail("VPN permission not granted")
                    runCatching { first?.close() }
                }
                builtWith = keys()
            }
            workers = listOf(scope.launch { statsLoop(p.id) }, scope.launch { healthLoop() })
            registerNetworkCallback()
            if (!reloadChain()) return
            try {
                startChain()
                startUpstream(p)
                if (!full) tun = buildTun(p, false) ?: run { stopKeepingTun(); return fail("VPN permission not granted") }
                if (full) startHev()
            } catch (e: IllegalArgumentException) {
                stopKeepingTun()
                return fail(e.message ?: "Invalid connection settings")
            } catch (e: Exception) {
                log("tunnel start failed", e)
                if (!onProxyFailure(e)) return
            }
            TunnelState.update {
                if (it.phase == Phase.PROXY_DOWN) it else it.copy(phase = Phase.CONNECTED, connectedAt = System.currentTimeMillis(), message = null)
            }
            launchCheck(force = false)
        }
    }

    /**
     * (Re)resolves the VPN to chain through (imported profile or a Mullvad server) and stops the old one.
     * Returns false if the tunnel was torn down.
     */
    private suspend fun reloadChain(): Boolean {
        WireGuard.stop()
        chainConfig = null
        val s = container.settings.current()
        if (!s.chainEnabled) {
            TunnelState.update { it.copy(chainName = null, vpnPingMs = null) }
            return true
        }
        return try {
            val (name, cfg) = container.resolveVpn(s)!!
            chainConfig = cfg
            chainBroken = false
            TunnelState.update { it.copy(chainName = name, vpnPingMs = null) }
            true
        } catch (e: Exception) {
            log("VPN chain", e)
            // Never fall back to an unchained connection: that would show the real IP to the proxy.
            TunnelState.update { it.copy(chainName = null) }
            chainBroken = true
            onProxyFailure(IOException("VPN: ${e.message}"))
        }
    }

    /** The VPN couldn't be set up; the upstream must not start without it. */
    private var chainBroken = false

    /** New sticky session (and/or new location from settings) while keeping the TUN up. */
    private suspend fun switchSession(sessionId: String) {
        lock.withLock {
            if (profile == null) return
            blockTraffic()
            container.settings.update { it.withSession(sessionId) }
            val p = container.connectionProfile(sessionId) ?: return
            profile = p
            val label = container.settings.current().location.label
            TunnelState.update {
                it.copy(phase = Phase.ROTATING, sessionId = sessionId, exitIp = null, report = null, profileName = displayName(p, label))
            }
            builtWith = keys()
            if (restartUpstream(p)) TunnelState.update { it.copy(phase = Phase.CONNECTED, message = null) }
        }
    }

    /** Re-reads the profile (credentials, gateway, protocol) and restarts the upstream. */
    private suspend fun rebuild(restartEngine: Boolean = false): Boolean {
        val p = container.connectionProfile() ?: return false
        val label = container.settings.current().location.label
        profile = p
        TunnelState.update { it.copy(profileName = displayName(p, label)) }
        return restartUpstream(p, restartEngine)
    }

    /**
     * Returns false if the upstream could not be restarted (state already updated). The relay keeps its port and the
     * tunnel engine keeps running unless [restartEngine]: restarting hev empties its fake-IP DNS table, and every
     * address apps still have cached (browsers keep them for a minute) then points nowhere or at a different site.
     */
    private fun restartUpstream(p: Profile, restartEngine: Boolean = false): Boolean {
        if (restartEngine) stopHev()
        httpProxy?.stop(); httpProxy = null
        systemProxy?.stop(); systemProxy = null
        // Refuse new app connections until the new upstream is up (never via an old, possibly unchained one).
        socksBridge?.let { it.upstream = null; it.closeConnections() }
        if (p.tunnelMode != TunnelMode.FULL) { socksBridge?.stop(); socksBridge = null }
        upstream = null
        return try {
            startChain()
            startUpstream(p)
            if (p.tunnelMode == TunnelMode.FULL && !hevRunning) startHev()
            if (p.tunnelMode == TunnelMode.PROXY_ONLY) {
                // The system proxy port is fixed at establish() time; rebuild the TUN to point at the new one.
                val old = tun
                tun = buildTun(p, bypassLan = false)
                old?.close()
            }
            true
        } catch (e: Exception) {
            log("upstream restart failed", e)
            onProxyFailure(e)
            false
        }
    }

    /**
     * Kill switch on: stay up with traffic blocked and keep retrying. Off: disconnect so traffic goes direct.
     * Returns true if the tunnel stays up.
     */
    private fun onProxyFailure(e: Throwable): Boolean {
        val msg = describe(e)
        return if (killSwitch) {
            TunnelState.update { it.copy(phase = Phase.PROXY_DOWN, checking = false, message = "$msg — traffic blocked") }
            true
        } else {
            teardown()
            fail("$msg — traffic goes direct (kill switch is off)")
            false
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is ProxyAuthException -> "${profile?.provider?.label ?: "Proxy"} rejected the proxy username/password (Settings → Accounts)"
        is UnknownHostException -> "No network / can't resolve proxy host"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun teardown() {
        checkJob?.cancel(); checkJob = null
        gate.close(); cleanIp = null; waitingForUser = false
        workers.forEach { it.cancel() }; workers = emptyList()
        unregisterNetworkCallback()
        stopHev()
        runCatching { tun?.close() }; tun = null
        httpProxy?.stop(); httpProxy = null
        systemProxy?.stop(); systemProxy = null
        socksBridge?.stop(); socksBridge = null
        upstream = null
        WireGuard.stop()
        chainConfig = null
        chainBroken = false
        configFile.delete()
        profile = null
        builtWith = null
        proxyRtt = null
        failedRepairs = 0
        hevBase = 0L to 0L; hevLast = 0L to 0L
        TunnelState.update { if (it.phase == Phase.ERROR) it else TunnelStatus() }
    }

    /** teardown() but leaves the TUN open: with the kill switch, a failed reconnect keeps blocking traffic. */
    private fun teardownKeepingTun() {
        val keep = tun
        tun = null
        teardown()
        tun = keep
    }

    /**
     * The tunnel couldn't be (re)built. If the user wants it on, stay in the foreground and let the supervisor retry
     * with backoff (traffic stays blocked with the kill switch). Without the user wanting it, or without VPN
     * permission, stop.
     */
    private fun fail(message: String) {
        val retry = wantConnected && !userStopped && VpnService.prepare(this) == null
        log("failed: $message" + if (retry) " (retrying)" else " (stopping)")
        if (retry) {
            TunnelState.update {
                TunnelStatus(phase = Phase.PROXY_DOWN, message = "$message — retrying", trafficBlocked = true, repairs = it.repairs)
            }
        } else {
            TunnelState.update { TunnelStatus(phase = Phase.ERROR, message = message) }
            stopService()
        }
    }

    private fun stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun displayName(p: Profile, location: String) = "${p.name} · $location"

    // endregion

    // region plumbing

    /** Starts the WireGuard VPN used for chaining, if enabled and not already up. */
    private fun startChain() {
        if (chainBroken) throw IOException("VPN is not available")
        val cfg = chainConfig ?: return
        if (!WireGuard.running) {
            WireGuard.start(cfg)
            chainStartedAt = System.currentTimeMillis()
        }
    }

    /** Local servers; with chaining, the proxy is reached via a WireGuard forwarder on 127.0.0.1. */
    private fun startUpstream(p: Profile) {
        val direct = Presets.build(p)
        require(direct.host.isNotBlank()) { "Proxy host is empty" }
        require(direct.username.isNotBlank()) { "Username is empty" }
        val ep = if (chainConfig != null) direct.copy(host = "127.0.0.1", port = WireGuard.forward(direct.host, direct.port)) else direct
        val up = Upstream(ep, onRtt = ::recordRtt)
        upstream = up
        httpProxy = LocalHttpProxy(up).also { it.start() }
        // hev always goes through our relay: fresh DNS per connection and visibility into whether app traffic works.
        if (p.tunnelMode == TunnelMode.FULL) {
            val bridge = socksBridge
            if (bridge != null) bridge.upstream = up
            else socksBridge = LocalSocksServer(up, relayHealth, gate, lanDirect = { bypassLan }).also { it.start() }
        } else {
            systemProxy = LocalHttpProxy(up, gate).also { it.start() }
        }
    }

    private fun recordRtt(ms: Long) {
        val prev = proxyRtt
        proxyRtt = if (prev == null) ms.toDouble() else prev * 0.7 + ms * 0.3
    }

    private fun startHev() {
        val fd = tun ?: return
        val bridge = socksBridge ?: error("Relay not started")
        configFile.writeText(HevConfig.build("127.0.0.1", bridge.port, null, null))
        if (!TunnelNative.TProxyStartService(configFile.absolutePath, fd.fd)) error("Tunnel engine failed to start")
        hevRunning = true
    }

    private fun stopHev() {
        if (hevRunning) {
            runCatching { TunnelNative.TProxyStopService() }
            hevRunning = false
        }
    }

    private fun buildTun(p: Profile, bypassLan: Boolean): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(p.name)
            .setMtu(HevConfig.MTU)
            .addAddress(HevConfig.TUN_V4, 32)
            .addAddress(HevConfig.TUN_V6, 128)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        // With "Block connections without VPN" Android drops everything routed outside the VPN, LAN included.
        // Then the LAN stays in the TUN and the relay connects LAN targets directly instead.
        val lan = bypassLan && p.tunnelMode == TunnelMode.FULL && !lockdown()
        Routes.v4(lan).forEach { b.addRoute(it.address, it.prefix) }
        Routes.v6(lan).forEach { b.addRoute(it.address, it.prefix) }
        if (p.tunnelMode == TunnelMode.FULL) {
            b.addDnsServer(HevConfig.MAPDNS)
        } else if (Build.VERSION.SDK_INT >= 29) {
            // Proxy-only: proxy-aware apps use the local proxy; everything else hits an unread TUN (blocked).
            b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", systemProxy!!.port))
        }
        applySplit(b, p)
        return b.establish()
    }

    private fun lockdown() = Build.VERSION.SDK_INT >= 29 && isLockdownEnabled

    /** Closes the gate and drops apps' open connections: the exit IP is new or not (yet) known to be clean. */
    private fun blockTraffic() {
        gate.close()
        cleanIp = null
        waitingForUser = false
        socksBridge?.closeConnections()
        systemProxy?.closeConnections()
        TunnelState.update { it.copy(trafficBlocked = true) }
    }

    private fun allowTraffic(ip: String?) {
        cleanIp = ip
        waitingForUser = false
        gate.open()
        TunnelState.update { it.copy(trafficBlocked = false) }
    }

    private fun applySplit(b: Builder, p: Profile) {
        val others = p.apps - packageName
        fun installed(pkg: String) = try {
            packageManager.getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }

        if (p.splitMode == SplitMode.ONLY_SELECTED && others.any(::installed)) {
            others.filter(::installed).forEach { b.addAllowedApplication(it) }
        } else {
            // Always exclude ourselves: relay / WireGuard / reputation sockets must not loop into the TUN.
            b.addDisallowedApplication(packageName)
            if (p.splitMode == SplitMode.EXCEPT_SELECTED) others.filter(::installed).forEach { b.addDisallowedApplication(it) }
        }
    }

    /** Wi-Fi ↔ mobile switches leave proxy and VPN sockets dead; rebuild them on the new network. */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val known = java.util.Collections.synchronizedSet(mutableSetOf<Network>())
        var lostAny = false
        var pending: Job? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val isNew = known.add(network)
                // Initial callbacks just list what's there; a network appearing after one was lost is a switch.
                if (isNew && lostAny) {
                    log("network switched to $network")
                    pending?.cancel()
                    pending = scope.launch { delay(2000); repair("network changed", force = true) }
                }
            }

            override fun onLost(network: Network) {
                known.remove(network)
                lostAny = true
                log("network lost: $network")
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { netCallback = cb }
            .onFailure { log("network callback", it) }
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null
    }

    /**
     * Rebuilds VPN chain, relay and tunnel engine while keeping the TUN up. Rate-limited unless [force];
     * after repeated repairs that didn't help, reconnects from scratch.
     */
    private suspend fun repair(reason: String, force: Boolean = false) {
        if (!TunnelState.status.value.isActive) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRepairAt < 60_000) return
        lastRepairAt = now
        log("repairing connection: $reason")
        val retry = reason == "retrying"
        if (!retry && failedRepairs >= 3) {
            // Repairs didn't help: rebuild from scratch. In its own job: this may run inside the health loop, which
            // connect's teardown cancels (that used to abort the reconnect and leave the tunnel down for good).
            failedRepairs = 0
            log("repairs didn't help ($reason), reconnecting")
            reconnectAsync("repair: $reason")
            return
        }
        if (!retry) failedRepairs++
        val mullvad = VpnExit.parse(container.settings.current().vpnExit) is VpnExit.Mullvad
        lock.withLock {
            if (profile == null) return
            if (chainConfig != null && mullvad && reason.startsWith("VPN")) {
                // The device may have been removed on the Mullvad website: then this install is signed out (no silent
                // re-registration, which would take a slot) and the chain reports it.
                container.verifyMullvadDevice()
            }
            chainBroken = false
            if (!reloadChain()) return
            builtWith = keys()
            val wasDown = TunnelState.status.value.phase == Phase.PROXY_DOWN
            if (rebuild(restartEngine = reason.startsWith(ENGINE_REASON))) {
                TunnelState.update {
                    val repairs = if (retry) it.repairs else it.repairs + 1
                    // Rebuilt, but not known to work yet: the next probe (health loop / check) says CONNECTED.
                    if (wasDown) it.copy(repairs = repairs, message = "Rebuilt the connection ($reason) — waiting for the proxy")
                    else if (retry) it.copy(phase = Phase.CONNECTED, message = null)
                    else it.copy(phase = Phase.CONNECTED, repairs = repairs, message = "Connection repaired ($reason)")
                }
            }
        }
        launchCheck(force = false)
    }

    // endregion

    // region background work

    private suspend fun statsLoop(statsKey: String) {
        var lastDown = 0L; var lastUp = 0L
        var savedDown = 0L; var savedUp = 0L
        var tick = 0
        val nm = getSystemService(NotificationManager::class.java)
        try {
            while (scope.isActive) {
                delay(1000)
                val (down, up) = counters()
                val dDown = (down - lastDown).coerceAtLeast(0); val dUp = (up - lastUp).coerceAtLeast(0)
                lastDown = down; lastUp = up
                TunnelState.update {
                    it.copy(downBps = dDown, upBps = dUp, sessionDown = it.sessionDown + dDown, sessionUp = it.sessionUp + dUp)
                }
                tick++
                if (tick % 2 == 0) nm.notify(NOTIFICATION_ID, buildNotification())
                if (tick % 10 == 0) {
                    val s = TunnelState.status.value
                    container.stats.add(statsKey, s.sessionDown - savedDown, s.sessionUp - savedUp)
                    savedDown = s.sessionDown; savedUp = s.sessionUp
                }
            }
        } finally {
            val s = TunnelState.status.value
            container.appScope.launch { container.stats.add(statsKey, s.sessionDown - savedDown, s.sessionUp - savedUp) }
        }
    }

    /** Monotonic (down, up) byte counters; hev counters reset on restart, so we track deltas across restarts. */
    private var hevBase = 0L to 0L
    private var hevLast = 0L to 0L
    private fun counters(): Pair<Long, Long> {
        val p = profile ?: return hevLast
        if (p.tunnelMode == TunnelMode.PROXY_ONLY) {
            val h = systemProxy ?: return hevLast
            return h.bytesDown.get() to h.bytesUp.get()
        }
        if (!hevRunning) return hevLast
        val s = runCatching { TunnelNative.TProxyGetStats() }.getOrNull() ?: return hevLast
        // hev counts from its own side: rx = written to the TUN (apps' download), tx = read from it (apps' upload).
        val raw = s[3] to s[1]
        if (raw.first + hevBase.first < hevLast.first || raw.second + hevBase.second < hevLast.second) hevBase = hevLast
        hevLast = (raw.first + hevBase.first) to (raw.second + hevBase.second)
        return hevLast
    }

    /**
     * Every few seconds: are apps' connections through the relay getting answers, is the VPN handshake
     * fresh, latencies. Every [dev.resproxy.data.AppSettings.healthIntervalSec]: an end-to-end probe.
     * When the proxy answers our probe but app traffic or the VPN is broken, rebuilds the plumbing.
     */
    private suspend fun healthLoop() {
        var fails = 0
        var deadExitProbes = 0
        var lastProbe = System.currentTimeMillis()
        var lastPing = 0L
        var seenOk = relayHealth.ok.get()
        var seenFail = relayHealth.failed.get()
        var failingSince = 0L
        var failsInStreak = 0L
        var quietSince = 0L
        var appUpAtQuiet = 0L
        var acceptedAtQuiet = -1L
        var engineDeadLogged = false
        var staleSeen = relayHealth.staleFakeIps.get()
        var staleLoggedAt = 0L
        var summaryAt = System.currentTimeMillis()
        while (scope.isActive) {
            delay(TICK_MS)
            if (kicked) { kicked = false; lastProbe = 0; fails = minOf(fails, 1) }
            val now = System.currentTimeMillis()
            val interval = container.settings.current().healthIntervalSec.coerceIn(10, 600) * 1000L
            val down = TunnelState.status.value.phase == Phase.PROXY_DOWN
            var suspect: String? = null

            // 1. Real traffic: only failures and no successes for 20 s means apps have no internet.
            val ok = relayHealth.ok.get(); val failed = relayHealth.failed.get()
            val newOk = ok - seenOk; val newFail = failed - seenFail
            seenOk = ok; seenFail = failed
            if (newOk > 0) {
                failingSince = 0; failsInStreak = 0; failedRepairs = 0
            } else if (newFail > 0) {
                if (failingSince == 0L) failingSince = now
                failsInStreak += newFail
            }
            if (!gate.isOpen) { failingSince = 0; failsInStreak = 0 }
            if (failingSince != 0L && now - failingSince > 20_000 && failsInStreak >= 3 &&
                relayHealth.distinctFailedHosts(failingSince) >= 2
            ) {
                suspect = "apps' connections were failing"
                log("relay: $failsInStreak failures, no successes; last: ${relayHealth.lastError}")
            }

            // 2. Tunnel engine: its thread must be alive, and apps' packets must turn into relay connections. With no
            //    relay connection open or opened for 30 s, everything apps send is new connections that never arrive.
            val bridge = socksBridge
            if (profile?.tunnelMode == TunnelMode.FULL && hevRunning && bridge != null) {
                if (TunnelNative.TProxyIsRunning()) engineDeadLogged = false
                val accepted = relayHealth.accepted.get()
                val appUp = hevLast.second
                if (!TunnelNative.TProxyIsRunning()) {
                    if (!engineDeadLogged) log("tunnel engine stopped by itself")
                    engineDeadLogged = true
                    suspect = "$ENGINE_REASON stopped"
                } else if (accepted != acceptedAtQuiet || bridge.openCount > 0 || !gate.isOpen || quietSince == 0L) {
                    quietSince = now; acceptedAtQuiet = accepted; appUpAtQuiet = appUp
                } else if (now - quietSince > 30_000) {
                    if (appUp - appUpAtQuiet > 8_000) {
                        log("tunnel engine: apps sent ${appUp - appUpAtQuiet} B in ${(now - quietSince) / 1000} s, no relay connection")
                        suspect = "$ENGINE_REASON stalled"
                    }
                    quietSince = 0
                }
            }
            val stale = relayHealth.staleFakeIps.get()
            if (stale > staleSeen && now - staleLoggedAt > 60_000) {
                log("relay: ${stale - staleSeen} connection(s) to fake IPs the tunnel engine no longer knows")
                staleSeen = stale; staleLoggedAt = now
            }
            if (now - summaryAt > 15 * 60_000L) {
                summaryAt = now
                log("health: relay ${relayHealth.summary()}, open ${bridge?.openCount ?: 0}; engine ↓ ${hevLast.first} ↑ ${hevLast.second}")
            }

            // 3. VPN: WireGuard re-handshakes every 2 minutes; an old handshake means the VPN path is dead.
            val chain = chainConfig
            if (chain != null && WireGuard.running) {
                val age = WireGuard.handshakeAgeSec()
                if ((age == null && now - chainStartedAt > 20_000) || (age != null && age > 200)) suspect = "VPN handshake lost"
                if (now - lastPing > 10_000) {
                    lastPing = now
                    val target = chain.dns.firstOrNull { !it.contains(':') } ?: chain.dns.firstOrNull()
                    val ms = target?.let { WireGuard.ping(it) }
                    if (ms != null) TunnelState.update { it.copy(vpnPingMs = ms) }
                }
            }
            proxyRtt?.let { r -> TunnelState.update { it.copy(proxyPingMs = r.toLong()) } }

            // Blocked and no check running (it failed, or the proxy dropped mid-check): check again.
            if (!gate.isOpen && !waitingForUser && checkJob?.isActive != true && upstream != null &&
                TunnelState.status.value.phase == Phase.CONNECTED && now - lastCheckKick > 30_000
            ) {
                launchCheck(force = false)
            }

            val backoff = minOf(5_000L * (fails + 1), interval)
            val up = upstream
            if (up == null) {
                // Upstream never came up (e.g. no network / VPN down at connect): keep retrying.
                if (down && now - lastProbe >= backoff) {
                    lastProbe = now; fails++
                    repair("retrying", force = true)
                }
                continue
            }
            if (suspect == null && now - lastProbe < (if (down) backoff else interval)) continue
            lastProbe = now
            val result = runCatching { ProxyProbe.run(up) }
            if (result.isSuccess) {
                fails = 0; deadExitProbes = 0
                lock.withLock {
                    if (profile?.tunnelMode == TunnelMode.FULL && !hevRunning) runCatching { startHev() }.onFailure { log("tunnel engine start", it) }
                }
                if (TunnelState.status.value.phase == Phase.PROXY_DOWN) {
                    TunnelState.update { it.copy(phase = Phase.CONNECTED, message = "Proxy reachable again") }
                    if ((TunnelState.status.value.exitIp == null || !gate.isOpen) && checkJob?.isActive != true) launchCheck(force = false)
                }
                TunnelState.update { it.copy(latencyMs = result.getOrNull()) }
                if (suspect != null) {
                    failingSince = 0; failsInStreak = 0
                    repair(suspect)
                }
            } else {
                fails++
                val e = result.exceptionOrNull()!!
                deadExitProbes = if (exitNodeDead(e)) deadExitProbes + 1 else 0
                if (deadExitProbes >= DEAD_EXIT_PROBES && profile?.sticky == true) {
                    // Repairs and reconnects reuse the same session, so they'd never get past a dead exit node.
                    log("exit node not answering (${e.message}), switching to a new session")
                    deadExitProbes = 0; fails = 0
                    switchSession(Presets.newSessionId())
                    TunnelState.update { it.copy(message = "Exit IP stopped working — switched to a new one") }
                    launchCheck(force = false)
                    continue
                }
                if (fails >= 2 || e is ProxyAuthException || down || suspect != null) {
                    if (!onProxyFailure(e)) return
                    if (e !is ProxyAuthException && (fails % 3 == 0 || suspect != null)) repair(suspect ?: "proxy unreachable", force = fails % 3 == 0)
                }
            }
        }
    }

    /**
     * Verifies the proxy works, then exit IP + reputation. Apps' traffic stays blocked until an IP passes
     * (verdict below the rotate threshold). With auto-rotate on, keeps switching sticky sessions until one does.
     */
    private fun launchCheck(force: Boolean) {
        checkJob?.cancel()
        lastCheckKick = System.currentTimeMillis()
        checkJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val up = upstream ?: return@launch
                val port = httpProxy?.port ?: return@launch
                TunnelState.update { it.copy(checking = true, rotationAttempt = attempt) }
                val latency = try {
                    ProxyProbe.run(up)
                } catch (e: Exception) {
                    TunnelState.update { it.copy(checking = false) }
                    onProxyFailure(e)
                    return@launch
                }
                TunnelState.update {
                    if (it.phase == Phase.PROXY_DOWN) it.copy(phase = Phase.CONNECTED, message = null, latencyMs = latency) else it.copy(latencyMs = latency)
                }
                val s = container.settings.current()
                if (!s.recheckOnConnect && !force) {
                    // Checks are turned off: nothing to wait for.
                    allowTraffic(null)
                    log("IP checks are off: traffic allowed")
                    TunnelState.update { it.copy(checking = false) }
                    return@launch
                }
                val report = try {
                    val exit = container.reputation.exitIp(port)
                    if (exit.ip != cleanIp) blockTraffic() // new IP, or it changed under us (provider ended the session)
                    TunnelState.update { it.copy(exitIp = exit.ip) }
                    container.reputation.check(exit.ip, force, fallbackProxyPort = port)
                } catch (e: Exception) {
                    // Torn down meanwhile (reconnect): this check is obsolete; its local proxy is gone.
                    if (!isActive) return@launch
                    // The health loop tries again.
                    TunnelState.update { it.copy(checking = false, message = "Couldn't check the IP (${e.message}) — traffic blocked, retrying") }
                    return@launch
                }
                TunnelState.update { it.copy(report = report, checking = false) }
                if (report.verdict == Verdict.UNKNOWN) {
                    TunnelState.update { it.copy(message = "No reputation source answered — traffic blocked, retrying") }
                    return@launch
                }

                val p = profile ?: return@launch
                if (report.verdict.severity < s.rotateThreshold.severity) {
                    allowTraffic(report.ip)
                    log("exit IP ${report.ip} is ${verdictWord(report.verdict)}: traffic allowed")
                    TunnelState.update { it.copy(message = null) }
                    if (attempt > 0) notifyRotation("Found ${verdictWord(report.verdict)} IP ${report.ip} after $attempt rotation(s)")
                    break
                }
                blockTraffic()
                if (!s.autoRotate || !p.sticky) {
                    waitingForUser = true
                    log("exit IP ${report.ip} is ${verdictWord(report.verdict)}, auto-rotate off: blocked until New IP")
                    TunnelState.update { it.copy(message = "IP is ${verdictWord(report.verdict)} — traffic blocked. Tap New IP.") }
                    break
                }
                // No limit: keep rotating until an IP passes. After the first quick tries, pace them.
                if (attempt >= QUICK_ROTATIONS) {
                    if (attempt == QUICK_ROTATIONS) notifyRotation("No clean IP after $attempt tries — traffic blocked, still trying")
                    TunnelState.update { it.copy(message = "No clean IP after $attempt tries — traffic blocked, trying again shortly") }
                    delay(ROTATION_PAUSE_MS)
                }
                attempt++
                switchSession(Presets.newSessionId())
            }
        }
    }

    private fun verdictWord(v: Verdict) = v.name.lowercase()

    // endregion

    // region notifications

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun buildNotification(): Notification {
        val s = TunnelState.status.value
        val title = when (s.phase) {
            Phase.CONNECTED -> if (s.trafficBlocked) "Traffic blocked until a clean IP is found" else "Connected · ${s.profileName ?: ""}"
            Phase.CONNECTING -> "Connecting…"
            Phase.ROTATING -> "Getting a new IP…"
            Phase.PROXY_DOWN -> "Proxy unreachable — traffic blocked"
            else -> "ResProxy"
        }
        val verdict = s.report?.verdict?.takeIf { it != Verdict.UNKNOWN }?.let(::verdictWord)
        val text = buildString {
            s.exitIp?.let { append(it) }
            verdict?.let { append(" · ").append(it) }
            if (s.phase == Phase.CONNECTED) append(" · ↓ ${formatBytes(s.downBps)}/s ↑ ${formatBytes(s.upBps)}/s")
        }.trim(' ', '·')
        return NotificationCompat.Builder(this, App.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "New IP", servicePending(ACTION_ROTATE, 1))
            .addAction(0, "Disconnect", servicePending(ACTION_DISCONNECT, 2))
            .build()
    }

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ProxyVpnService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun notifyRotation(text: String) {
        val n = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("IP reputation")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }

    // endregion

    companion object {
        /** A service instance exists in this process; it keeps itself running (see `supervise`). */
        @Volatile var alive = false
            private set
        const val ACTION_CONNECT = "dev.resproxy.CONNECT"
        const val ACTION_DISCONNECT = "dev.resproxy.DISCONNECT"
        const val ACTION_ROTATE = "dev.resproxy.ROTATE"
        const val ACTION_APPLY_LOCATION = "dev.resproxy.APPLY_LOCATION"
        const val ACTION_RECHECK = "dev.resproxy.RECHECK"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val TAG = "ResProxy"
        private const val TICK_MS = 5_000L
        private const val SUPERVISE_TICK_MS = 5_000L
        /** Rotations in a row without a pause; after that one every [ROTATION_PAUSE_MS]. */
        private const val QUICK_ROTATIONS = 10
        private const val ROTATION_PAUSE_MS = 20_000L
        /** Probes in a row where the proxy answered but couldn't reach the target: the sticky exit node is gone. */
        private const val DEAD_EXIT_PROBES = 2
        /** Repair reasons starting with this restart hev itself; all other repairs leave it (and its DNS table) alone. */
        private const val ENGINE_REASON = "tunnel engine"
    }
}
