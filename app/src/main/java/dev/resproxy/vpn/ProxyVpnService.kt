package dev.resproxy.vpn

import android.app.Notification
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.resproxy.App
import dev.resproxy.MainActivity
import dev.resproxy.R
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
import dev.resproxy.net.Upstream
import dev.resproxy.net.WireGuard
import dev.resproxy.net.WireGuardConfig
import dev.resproxy.provider.Presets
import dev.resproxy.reputation.ReputationReport
import dev.resproxy.ui.formatBytes
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
        Log.e(TAG, "background job failed, reconnecting", e)
        scope.launch {
            delay(3000)
            if (container.settings.current().wasConnected) connect()
        }
    }
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashHandler)
    private val lock = Mutex()

    private var tun: ParcelFileDescriptor? = null
    private var upstream: Upstream? = null
    private var httpProxy: LocalHttpProxy? = null
    private var socksBridge: LocalSocksServer? = null
    private var hevRunning = false
    private var profile: Profile? = null
    /** Set when the upstream proxy is reached through a WireGuard VPN. */
    private var chainConfig: WireGuardConfig? = null
    private var workers: List<Job> = emptyList()
    private var checkJob: Job? = null

    @Volatile private var killSwitch = true
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
        scope.launch { container.settings.settings.collect { killSwitch = it.killSwitch } }
        scope.launch { watchSettings() }
        scope.launch { watchApiKeys() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(buildNotification())
        val active = TunnelState.status.value.isActive
        when (intent?.action) {
            ACTION_DISCONNECT -> scope.launch {
                container.settings.update { it.copy(wasConnected = false) }
                lock.withLock { teardown() }
                stopService()
            }
            ACTION_ROTATE -> if (!active) stopService() else scope.launch {
                switchSession(Presets.newSessionId())
                launchCheck(force = true)
            }
            // Provider / location change: reuse that place's remembered session (same IP) if it's still alive.
            ACTION_APPLY_LOCATION -> if (!active) stopService() else scope.launch {
                switchSession(container.settings.current().sessionId.ifEmpty { Presets.newSessionId() })
                launchCheck(force = false)
            }
            ACTION_RECHECK -> if (!active) stopService() else launchCheck(force = true)
            // ACTION_CONNECT, always-on start (SERVICE_INTERFACE) and sticky restarts (null intent)
            else -> scope.launch { connect() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        scope.launch {
            container.settings.update { it.copy(wasConnected = false) }
            lock.withLock { teardown() }
            stopService()
        }
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
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
            tun = listOf(cs.tunnelMode, cs.splitMode, cs.apps.sorted(), s.bypassLan).toString(),
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
                        Log.i(TAG, "routing settings changed, reconnecting")
                        connect()
                    }
                    now.upstream != built.upstream -> {
                        Log.i(TAG, "connection settings changed, rebuilding upstream")
                        lock.withLock {
                            if (profile == null) return@withLock
                            builtWith = now
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
                Log.i(TAG, "API keys changed, re-checking reputation")
                launchCheck(force = true)
            }
        }
    }

    private suspend fun connect() {
        lock.withLock {
            // Keep the old TUN until the new one is established, so a reconnect never leaks traffic.
            val oldTun = tun
            tun = null
            teardown()
            var s = container.settings.current()
            if (s.connection.sticky) {
                // Remember the session for this provider + location (new one if none), so switching back reuses it.
                container.settings.update { it.withSession(it.sessionId.ifEmpty { Presets.newSessionId() }) }
                s = container.settings.current()
            }
            val p = container.connectionProfile()
            if (p == null) {
                oldTun?.close()
                return fail("Add your ${s.provider.label} account first")
            }
            profile = p
            builtWith = keys()
            TunnelState.update {
                TunnelStatus(phase = Phase.CONNECTING, profileId = p.id, profileName = displayName(p, s.location.label), sessionId = p.sessionId)
            }
            container.settings.update { it.copy(wasConnected = true) }
            val full = p.tunnelMode == TunnelMode.FULL
            // Full tunnel: capture all traffic of the routed apps before any upstream I/O.
            if (full) {
                tun = buildTun(p, s.bypassLan)
                oldTun?.close()
                if (tun == null) { teardown(); return fail("VPN permission not granted") }
            } else {
                oldTun?.close()
            }
            workers = listOf(scope.launch { statsLoop(p.id) }, scope.launch { healthLoop() })
            registerNetworkCallback()
            if (!reloadChain()) return
            try {
                startChain()
                startUpstream(p)
                if (!full) tun = buildTun(p, false) ?: run { teardown(); return fail("VPN permission not granted") }
                if (full) startHev()
            } catch (e: IllegalArgumentException) {
                teardown()
                return fail(e.message ?: "Invalid connection settings")
            } catch (e: Exception) {
                Log.w(TAG, "tunnel start failed", e)
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
            Log.w(TAG, "VPN chain", e)
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
    private suspend fun rebuild(): Boolean {
        val p = container.connectionProfile() ?: return false
        val label = container.settings.current().location.label
        profile = p
        TunnelState.update { it.copy(profileName = displayName(p, label)) }
        return restartUpstream(p)
    }

    /** Returns false if the upstream could not be restarted (state already updated). */
    private fun restartUpstream(p: Profile): Boolean {
        stopHev()
        httpProxy?.stop(); httpProxy = null
        socksBridge?.stop(); socksBridge = null
        upstream = null
        return try {
            startChain()
            startUpstream(p)
            if (p.tunnelMode == TunnelMode.FULL) startHev()
            if (p.tunnelMode == TunnelMode.PROXY_ONLY) {
                // The system proxy port is fixed at establish() time; rebuild the TUN to point at the new one.
                val old = tun
                tun = buildTun(p, bypassLan = false)
                old?.close()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "upstream restart failed", e)
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
            fail("$msg — disconnected (kill switch is off)")
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
        workers.forEach { it.cancel() }; workers = emptyList()
        unregisterNetworkCallback()
        stopHev()
        runCatching { tun?.close() }; tun = null
        httpProxy?.stop(); httpProxy = null
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

    private fun fail(message: String) {
        TunnelState.update { TunnelStatus(phase = Phase.ERROR, message = message) }
        stopService()
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
        if (p.tunnelMode == TunnelMode.FULL) socksBridge = LocalSocksServer(up, relayHealth).also { it.start() }
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
        val lan = bypassLan && p.tunnelMode == TunnelMode.FULL
        Routes.v4(lan).forEach { b.addRoute(it.address, it.prefix) }
        Routes.v6(lan).forEach { b.addRoute(it.address, it.prefix) }
        if (p.tunnelMode == TunnelMode.FULL) {
            b.addDnsServer(HevConfig.MAPDNS)
        } else if (Build.VERSION.SDK_INT >= 29) {
            // Proxy-only: proxy-aware apps use the local proxy; everything else hits an unread TUN (blocked).
            b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", httpProxy!!.port))
        }
        applySplit(b, p)
        return b.establish()
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
                    Log.i(TAG, "network switched to $network")
                    pending?.cancel()
                    pending = scope.launch { delay(2000); repair("network changed", force = true) }
                }
            }

            override fun onLost(network: Network) {
                known.remove(network)
                lostAny = true
                Log.i(TAG, "network lost: $network")
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { netCallback = cb }
            .onFailure { Log.w(TAG, "network callback", it) }
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
        Log.i(TAG, "repairing connection: $reason")
        val retry = reason == "retrying"
        if (!retry && failedRepairs >= 3) {
            failedRepairs = 0
            connect()
            TunnelState.update { it.copy(repairs = it.repairs + 1, message = "Reconnected ($reason)") }
            return
        }
        if (!retry) failedRepairs++
        val mullvad = VpnExit.parse(container.settings.current().vpnExit) is VpnExit.Mullvad
        lock.withLock {
            if (profile == null) return
            if (chainConfig != null && mullvad && reason.startsWith("VPN")) {
                // The device key may have been revoked on the Mullvad website; logging in re-registers it.
                runCatching { container.mullvadLogin() }
            }
            chainBroken = false
            if (!reloadChain()) return
            builtWith = keys()
            if (rebuild()) {
                TunnelState.update {
                    if (retry) it.copy(phase = Phase.CONNECTED, message = null)
                    else it.copy(phase = Phase.CONNECTED, repairs = it.repairs + 1, message = "Connection repaired ($reason)")
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
            val h = httpProxy ?: return hevLast
            return h.bytesDown.get() to h.bytesUp.get()
        }
        if (!hevRunning) return hevLast
        val s = runCatching { TunnelNative.TProxyGetStats() }.getOrNull() ?: return hevLast
        val raw = s[1] to s[3]
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
        var lastProbe = System.currentTimeMillis()
        var lastPing = 0L
        var seenOk = relayHealth.ok.get()
        var seenFail = relayHealth.failed.get()
        var failingSince = 0L
        var failsInStreak = 0L
        var quietSince = 0L
        var appUpAtQuiet = 0L
        while (scope.isActive) {
            delay(TICK_MS)
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
            if (failingSince != 0L && now - failingSince > 20_000 && failsInStreak >= 3 &&
                relayHealth.distinctFailedHosts(failingSince) >= 2
            ) {
                suspect = "apps' connections were failing"
                Log.i(TAG, "relay: $failsInStreak failures, no successes; last: ${relayHealth.lastError}")
            }

            // 2. Tunnel engine stuck: apps keep sending, but no connection reaches the relay.
            if (profile?.tunnelMode == TunnelMode.FULL && hevRunning) {
                val appUp = hevLast.second
                if (newOk + newFail > 0) quietSince = 0
                else if (quietSince == 0L) { quietSince = now; appUpAtQuiet = appUp }
                else if (now - quietSince > 30_000 && appUp - appUpAtQuiet > 20_000) { suspect = "tunnel engine stalled"; quietSince = 0 }
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
                fails = 0
                lock.withLock {
                    if (profile?.tunnelMode == TunnelMode.FULL && !hevRunning) runCatching { startHev() }
                }
                if (TunnelState.status.value.phase == Phase.PROXY_DOWN) {
                    TunnelState.update { it.copy(phase = Phase.CONNECTED, message = "Proxy reachable again") }
                    if (TunnelState.status.value.exitIp == null) launchCheck(force = false)
                }
                TunnelState.update { it.copy(latencyMs = result.getOrNull()) }
                if (suspect != null) {
                    failingSince = 0; failsInStreak = 0
                    repair(suspect)
                }
            } else {
                fails++
                val e = result.exceptionOrNull()!!
                if (fails >= 2 || e is ProxyAuthException || down || suspect != null) {
                    if (!onProxyFailure(e)) return
                    if (e !is ProxyAuthException && (fails % 3 == 0 || suspect != null)) repair(suspect ?: "proxy unreachable", force = fails % 3 == 0)
                }
            }
        }
    }

    /**
     * Verifies the proxy works, then exit IP + reputation. With auto-rotate on, keeps switching
     * sticky sessions until the verdict is below the threshold (or keeps the best one found).
     */
    private fun launchCheck(force: Boolean) {
        checkJob?.cancel()
        checkJob = scope.launch {
            var attempt = 0
            var best: Pair<String, ReputationReport>? = null
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
                    TunnelState.update { it.copy(checking = false) }
                    return@launch
                }
                val report = try {
                    val exit = container.reputation.exitIp(port)
                    TunnelState.update { it.copy(exitIp = exit.ip) }
                    container.reputation.check(exit.ip, force, fallbackProxyPort = port)
                } catch (e: Exception) {
                    TunnelState.update { it.copy(checking = false, message = e.message) }
                    return@launch
                }
                TunnelState.update { it.copy(report = report, checking = false) }

                val p = profile ?: return@launch
                val acceptable = report.verdict.severity < s.rotateThreshold.severity
                if (!s.autoRotate || !p.sticky || acceptable) {
                    if (attempt > 0) notifyRotation("Found ${verdictWord(report.verdict)} IP ${report.ip} after $attempt rotation(s)")
                    break
                }
                if (best == null || report.verdict.severity < best.second.verdict.severity) best = p.sessionId to report
                val max = s.maxRotations.coerceIn(1, 50)
                if (attempt >= max) {
                    val (sid, rep) = best!!
                    if (sid != p.sessionId) switchSession(sid)
                    TunnelState.update {
                        it.copy(report = rep, exitIp = rep.ip, message = "No clean IP after $attempt tries; kept the best one")
                    }
                    notifyRotation("No clean IP after $attempt rotations — kept ${rep.ip} (${verdictWord(rep.verdict)})")
                    break
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
            Phase.CONNECTED -> "Connected · ${s.profileName ?: ""}"
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
        const val ACTION_CONNECT = "dev.resproxy.CONNECT"
        const val ACTION_DISCONNECT = "dev.resproxy.DISCONNECT"
        const val ACTION_ROTATE = "dev.resproxy.ROTATE"
        const val ACTION_APPLY_LOCATION = "dev.resproxy.APPLY_LOCATION"
        const val ACTION_RECHECK = "dev.resproxy.RECHECK"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val TAG = "ResProxy"
        private const val TICK_MS = 5_000L
    }
}
