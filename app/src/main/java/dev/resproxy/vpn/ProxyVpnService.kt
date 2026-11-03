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
import dev.resproxy.net.Upstream
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
import java.net.InetAddress

/**
 * Owns the TUN interface and the upstream plumbing:
 * apps → TUN → hev-socks5-tunnel → (SOCKS5 proxy | [LocalSocksServer] → HTTP proxy).
 * The TUN fd stays open across rotations and outages, so traffic is held/dropped, never leaked.
 */
class ProxyVpnService : VpnService() {

    private val container by lazy { (application as App).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private var tun: ParcelFileDescriptor? = null
    private var httpProxy: LocalHttpProxy? = null
    private var socksBridge: LocalSocksServer? = null
    private var hevRunning = false
    private var profile: Profile? = null
    private var workers: List<Job> = emptyList()
    private var checkJob: Job? = null

    private val configFile by lazy { File(filesDir, "hev.yml") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(buildNotification())
        when (intent?.action) {
            ACTION_DISCONNECT -> scope.launch { lock.withLock { teardown() }; stopService() }
            ACTION_ROTATE, ACTION_RECHECK -> if (!TunnelState.status.value.isActive) {
                stopService()
            } else if (intent.action == ACTION_ROTATE) {
                scope.launch { rotate(); launchCheck(autoRotate = false, force = true) }
            } else {
                launchCheck(autoRotate = false, force = true)
            }
            // ACTION_CONNECT, always-on start (SERVICE_INTERFACE) and sticky restarts (null intent)
            else -> {
                val id = intent?.getStringExtra(EXTRA_PROFILE_ID)
                scope.launch { connect(id) }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        scope.launch { lock.withLock { teardown() }; stopService() }
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    // region lifecycle

    private suspend fun connect(requestedId: String?) {
        lock.withLock {
            teardown()
            val settings = container.settings.current()
            val p0 = container.profiles.get(requestedId ?: settings.selectedProfileId)
                ?: container.profiles.all().firstOrNull()
            if (p0 == null) return fail("No profile configured")
            val p = if (p0.sticky && p0.sessionId.isEmpty()) p0.copy(sessionId = Presets.newSessionId()).also { container.profiles.save(it) } else p0
            profile = p
            container.settings.update { it.copy(selectedProfileId = p.id) }
            TunnelState.update {
                TunnelStatus(phase = Phase.CONNECTING, profileId = p.id, profileName = p.name, sessionId = p.sessionId)
            }
            try {
                startUpstream(p)
                tun = buildTun(p) ?: return fail("VPN permission not granted")
                if (p.tunnelMode == TunnelMode.FULL) startHev(p)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                teardown()
                return fail(e.message ?: e.javaClass.simpleName)
            }
            TunnelState.update { it.copy(phase = Phase.CONNECTED, connectedAt = System.currentTimeMillis(), message = null) }
            workers = listOf(scope.launch { statsLoop(p.id) }, scope.launch { healthLoop() })
            if (settings.recheckOnConnect) launchCheck(autoRotate = true, force = false)
        }
    }

    /** Replaces the sticky session (new exit IP) while keeping the TUN up. */
    private suspend fun rotate(sessionId: String = Presets.newSessionId()) {
        lock.withLock {
            val p = profile ?: return
            val np = p.copy(sessionId = sessionId)
            container.profiles.save(np)
            profile = np
            TunnelState.update { it.copy(phase = Phase.ROTATING, sessionId = sessionId, exitIp = null, report = null) }
            restartUpstream(np)
            TunnelState.update { it.copy(phase = Phase.CONNECTED, message = null) }
        }
    }

    private fun restartUpstream(p: Profile) {
        stopHev()
        httpProxy?.stop(); httpProxy = null
        socksBridge?.stop(); socksBridge = null
        try {
            startUpstream(p)
            if (p.tunnelMode == TunnelMode.FULL) startHev(p)
            if (p.tunnelMode == TunnelMode.PROXY_ONLY) {
                // The system proxy port is fixed at establish() time; rebuild the TUN to point at the new one.
                val old = tun
                tun = buildTun(p)
                old?.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "upstream restart failed", e)
            TunnelState.update { it.copy(phase = Phase.PROXY_DOWN, message = e.message) }
        }
    }

    private fun teardown() {
        checkJob?.cancel(); checkJob = null
        workers.forEach { it.cancel() }; workers = emptyList()
        stopHev()
        runCatching { tun?.close() }; tun = null
        httpProxy?.stop(); httpProxy = null
        socksBridge?.stop(); socksBridge = null
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

    // endregion

    // region plumbing

    private fun startUpstream(p: Profile) {
        val ep = Presets.build(p)
        require(ep.host.isNotBlank()) { "Proxy host is empty" }
        val upstream = Upstream(ep)
        httpProxy = LocalHttpProxy(upstream).also { it.start() }
        if (p.tunnelMode == TunnelMode.FULL && ep.protocol == Protocol.HTTP) {
            socksBridge = LocalSocksServer(upstream).also { it.start() }
        }
    }

    private fun startHev(p: Profile) {
        val fd = tun ?: return
        val bridge = socksBridge
        val yaml = if (bridge != null) {
            HevConfig.build("127.0.0.1", bridge.port, null, null)
        } else {
            val ep = Presets.build(p)
            // Resolve here (we're outside the VPN) so hev gets a literal address.
            val addr = InetAddress.getByName(ep.host).hostAddress ?: ep.host
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

    private suspend fun statsLoop(profileId: String) {
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
                    container.stats.add(profileId, s.sessionDown - savedDown, s.sessionUp - savedUp)
                    savedDown = s.sessionDown; savedUp = s.sessionUp
                }
            }
        } finally {
            val s = TunnelState.status.value
            container.appScope.launch { container.stats.add(profileId, s.sessionDown - savedDown, s.sessionUp - savedUp) }
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
            val interval = container.settings.current().healthIntervalSec.coerceIn(10, 600)
            delay((if (fails > 0) minOf(5L * fails, interval.toLong()) else interval.toLong()) * 1000)
            val port = httpProxy?.port ?: continue
            val latency = container.reputation.probe(port)
            if (latency != null) {
                if (TunnelState.status.value.phase == Phase.PROXY_DOWN) {
                    TunnelState.update { it.copy(phase = Phase.CONNECTED, message = "Proxy reachable again") }
                }
                TunnelState.update { it.copy(latencyMs = latency) }
                fails = 0
            } else {
                fails++
                if (fails >= 2) {
                    TunnelState.update { it.copy(phase = Phase.PROXY_DOWN, message = "Proxy unreachable — traffic blocked until it recovers") }
                    if (fails % 3 == 0) lock.withLock { profile?.let { restartUpstream(it) } }
                }
            }
        }
    }

    /** Exit IP + reputation; with [autoRotate], keeps rotating sticky sessions until the verdict is acceptable. */
    private fun launchCheck(autoRotate: Boolean, force: Boolean) {
        checkJob?.cancel()
        checkJob = scope.launch {
            var attempt = 0
            var best: Pair<String, ReputationReport>? = null
            while (isActive) {
                val port = httpProxy?.port ?: return@launch
                TunnelState.update { it.copy(checking = true, rotationAttempt = attempt) }
                val report = try {
                    val exit = container.reputation.exitIp(port)
                    TunnelState.update { it.copy(exitIp = exit.ip, latencyMs = exit.latencyMs) }
                    container.reputation.check(exit.ip, force, fallbackProxyPort = port)
                } catch (e: Exception) {
                    TunnelState.update { it.copy(checking = false, message = e.message) }
                    return@launch
                }
                TunnelState.update { it.copy(report = report, checking = false) }

                val s = container.settings.current()
                val p = profile ?: return@launch
                val acceptable = report.verdict.severity < s.rotateThreshold.severity
                if (!autoRotate || !s.autoRotate || !p.sticky || acceptable) {
                    if (attempt > 0) notifyRotation("Found ${report.verdict.name.lowercase()} IP ${report.ip} after $attempt rotation(s)")
                    break
                }
                if (best == null || report.verdict.severity < best.second.verdict.severity) best = p.sessionId to report
                if (attempt >= s.maxRotations.coerceIn(1, 20)) {
                    val (sid, rep) = best!!
                    if (sid != p.sessionId) rotate(sid)
                    TunnelState.update { it.copy(report = rep, exitIp = rep.ip, message = "No IP below ${s.rotateThreshold.name.lowercase()} after $attempt tries; kept the best one") }
                    notifyRotation("No clean IP after $attempt rotations — kept ${rep.ip} (${rep.verdict.name.lowercase()})")
                    break
                }
                attempt++
                rotate()
            }
        }
    }

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
        val verdict = s.report?.verdict?.takeIf { it != Verdict.UNKNOWN }?.name?.lowercase()
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
        const val ACTION_RECHECK = "dev.resproxy.RECHECK"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val TAG = "ResProxy"
    }
}
