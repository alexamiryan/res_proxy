package dev.resproxy.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import dev.resproxy.data.Protocol
import dev.resproxy.data.SplitMode
import dev.resproxy.data.TunnelMode
import dev.resproxy.data.Verdict
import dev.resproxy.net.LocalHttpProxy
import dev.resproxy.net.LocalSocksServer
import dev.resproxy.net.ProxyAuthException
import dev.resproxy.net.ProxyProbe
import dev.resproxy.net.Upstream
import dev.resproxy.net.WireGuard
import dev.resproxy.net.WireGuardConfig
import dev.resproxy.provider.Presets
import dev.resproxy.reputation.ReputationReport
import dev.resproxy.ui.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import dev.resproxy.net.Resolver
import java.net.UnknownHostException

/**
 * Owns the TUN interface and the upstream plumbing:
 * apps → TUN → hev-socks5-tunnel → (SOCKS5 proxy | [LocalSocksServer] → HTTP proxy).
 *
 * The TUN is established before anything touches the network and stays up across rotations,
 * location changes and outages, so with the kill switch on traffic is held/dropped, never sent direct.
 */
class ProxyVpnService : VpnService() {

    private val container by lazy { (application as App).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private var tun: ParcelFileDescriptor? = null
    private var upstream: Upstream? = null
    private var httpProxy: LocalHttpProxy? = null
    private var socksBridge: LocalSocksServer? = null
    private var hevRunning = false
    private var profile: Profile? = null
    /** Set when the upstream proxy is reached through the imported WireGuard VPN. */
    private var chainConfig: WireGuardConfig? = null
    private var workers: List<Job> = emptyList()
    private var checkJob: Job? = null

    @Volatile private var killSwitch = true

    private val configFile by lazy { File(filesDir, "hev.yml") }

    override fun onCreate() {
        super.onCreate()
        scope.launch { container.settings.settings.collect { killSwitch = it.killSwitch } }
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
            ACTION_ROTATE, ACTION_APPLY_LOCATION -> if (!active) stopService() else scope.launch {
                switchSession(Presets.newSessionId())
                launchCheck(force = intent.action == ACTION_ROTATE)
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

    private suspend fun connect() {
        lock.withLock {
            teardown()
            var s = container.settings.current()
            if (s.connection.sticky && s.sessionId.isEmpty()) {
                container.settings.update { it.copy(sessionId = Presets.newSessionId()) }
                s = container.settings.current()
            }
            val p = container.connectionProfile() ?: return fail("Add your ${s.provider.label} account first")
            profile = p
            TunnelState.update {
                TunnelStatus(phase = Phase.CONNECTING, profileId = p.id, profileName = displayName(p, s.location.label), sessionId = p.sessionId)
            }
            container.settings.update { it.copy(wasConnected = true) }
            if (s.chainEnabled) {
                val chain = container.chain.get() ?: run { teardown(); return fail("VPN chain is on but no WireGuard config is imported") }
                chainConfig = try {
                    WireGuardConfig.parse(chain.config)
                } catch (e: Exception) {
                    teardown(); return fail("WireGuard config: ${e.message}")
                }

                TunnelState.update { it.copy(chainName = chain.name) }
            }
            val full = p.tunnelMode == TunnelMode.FULL
            // Full tunnel: capture all traffic of the routed apps before any upstream I/O.
            if (full) tun = buildTun(p) ?: run { teardown(); return fail("VPN permission not granted") }
            workers = listOf(scope.launch { statsLoop(p.id) }, scope.launch { healthLoop() })
            try {
                startChain()
                startUpstream(p)
                if (!full) tun = buildTun(p) ?: run { teardown(); return fail("VPN permission not granted") }
                if (full) startHev(p)
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

    /** New sticky session (and/or new location from settings) while keeping the TUN up. */
    private suspend fun switchSession(sessionId: String) {
        lock.withLock {
            if (profile == null) return
            container.settings.update { it.copy(sessionId = sessionId) }
            val p = container.connectionProfile(sessionId) ?: return
            profile = p
            val label = container.settings.current().location.label
            TunnelState.update {
                it.copy(phase = Phase.ROTATING, sessionId = sessionId, exitIp = null, report = null, profileName = displayName(p, label))
            }
            if (restartUpstream(p)) TunnelState.update { it.copy(phase = Phase.CONNECTED, message = null) }
        }
    }

    /** Returns false if the upstream could not be restarted (state already updated). */
    private fun restartUpstream(p: Profile): Boolean {
        stopHev()
        httpProxy?.stop(); httpProxy = null
        socksBridge?.stop(); socksBridge = null
        return try {
            startChain()
            startUpstream(p)
            if (p.tunnelMode == TunnelMode.FULL) startHev(p)
            if (p.tunnelMode == TunnelMode.PROXY_ONLY) {
                // The system proxy port is fixed at establish() time; rebuild the TUN to point at the new one.
                val old = tun
                tun = buildTun(p)
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
        is ProxyAuthException -> "${profile?.provider?.label ?: "Proxy"} rejected the proxy username/password (Settings → Account)"
        is UnknownHostException -> "No network / can't resolve proxy host"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun teardown() {
        checkJob?.cancel(); checkJob = null
        workers.forEach { it.cancel() }; workers = emptyList()
        stopHev()
        runCatching { tun?.close() }; tun = null
        httpProxy?.stop(); httpProxy = null
        socksBridge?.stop(); socksBridge = null
        upstream = null
        WireGuard.stop()
        chainConfig = null
        configFile.delete()
        profile = null
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
        val cfg = chainConfig ?: return
        if (!WireGuard.running) WireGuard.start(cfg)
    }

    /** Local servers; with chaining, the proxy is reached via a WireGuard forwarder on 127.0.0.1. */
    private fun startUpstream(p: Profile) {
        val direct = Presets.build(p)
        require(direct.host.isNotBlank()) { "Proxy host is empty" }
        require(direct.username.isNotBlank()) { "Username is empty" }
        val ep = if (chainConfig != null) direct.copy(host = "127.0.0.1", port = WireGuard.forward(direct.host, direct.port)) else direct
        val up = Upstream(ep)
        upstream = up
        httpProxy = LocalHttpProxy(up).also { it.start() }
        if (p.tunnelMode == TunnelMode.FULL && ep.protocol == Protocol.HTTP) {
            socksBridge = LocalSocksServer(up).also { it.start() }
        }
    }

    private fun startHev(p: Profile) {
        val fd = tun ?: return
        val bridge = socksBridge
        val yaml = if (bridge != null) {
            HevConfig.build("127.0.0.1", bridge.port, null, null)
        } else {
            val ep = upstream?.endpoint ?: error("Upstream not started")
            // Resolve here (we're outside the VPN) so hev gets a literal address.
            val addr = Resolver.resolve(ep.host).hostAddress ?: ep.host
            HevConfig.build(addr, ep.port, ep.username, ep.password)
        }
        configFile.writeText(yaml)
        if (!TunnelNative.TProxyStartService(configFile.absolutePath, fd.fd)) error("Tunnel engine failed to start")
        hevRunning = true
    }

    private fun stopHev() {
        if (hevRunning) {
            runCatching { TunnelNative.TProxyStopService() }
            hevRunning = false
        }
    }

    private fun buildTun(p: Profile): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(p.name)
            .setMtu(HevConfig.MTU)
            .addAddress(HevConfig.TUN_V4, 32)
            .addAddress(HevConfig.TUN_V6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
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
            // Always exclude ourselves: hev / bridge / reputation sockets must not loop into the TUN.
            b.addDisallowedApplication(packageName)
            if (p.splitMode == SplitMode.EXCEPT_SELECTED) others.filter(::installed).forEach { b.addDisallowedApplication(it) }
        }
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

    private suspend fun healthLoop() {
        var fails = 0
        while (scope.isActive) {
            val interval = container.settings.current().healthIntervalSec.coerceIn(10, 600).toLong()
            val down = TunnelState.status.value.phase == Phase.PROXY_DOWN
            delay(if (down) minOf(5L * (fails + 1), interval) * 1000 else interval * 1000)
            val up = upstream
            if (up == null) {
                // Upstream never came up (e.g. no network / VPN chain down at connect): keep retrying.
                if (down) lock.withLock { profile?.let { restartUpstream(it) } }
                continue
            }
            val result = runCatching { ProxyProbe.run(up) }
            if (result.isSuccess) {
                fails = 0
                lock.withLock {
                    val p = profile
                    if (p != null && p.tunnelMode == TunnelMode.FULL && !hevRunning) runCatching { startHev(p) }
                }
                if (TunnelState.status.value.phase == Phase.PROXY_DOWN) {
                    TunnelState.update { it.copy(phase = Phase.CONNECTED, message = "Proxy reachable again") }
                    if (TunnelState.status.value.exitIp == null) launchCheck(force = false)
                }
                TunnelState.update { it.copy(latencyMs = result.getOrNull()) }
            } else {
                fails++
                val e = result.exceptionOrNull()!!
                if (fails >= 2 || e is ProxyAuthException || down) {
                    if (!onProxyFailure(e)) return
                    if (fails % 3 == 0) lock.withLock { profile?.let { restartUpstream(it) } }
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
                TunnelState.update { it.copy(report = report, checking = false, message = null) }

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
    }
}
