package dev.resproxy.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.resproxy.reputation.ReputationReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, ROTATING, PROXY_DOWN, ERROR }

data class TunnelStatus(
    val phase: Phase = Phase.DISCONNECTED,
    val profileId: String? = null,
    val profileName: String? = null,
    val sessionId: String? = null,
    val exitIp: String? = null,
    val latencyMs: Long? = null,
    val report: ReputationReport? = null,
    val checking: Boolean = false,
    val rotationAttempt: Int = 0,
    val message: String? = null,
    val downBps: Long = 0,
    val upBps: Long = 0,
    val sessionDown: Long = 0,
    val sessionUp: Long = 0,
    val connectedAt: Long = 0,
    /** Name of the WireGuard VPN the proxy connection is chained through, if any. */
    val chainName: String? = null,
    /** Round trip to the VPN server (in-tunnel ping), when chaining. */
    val vpnPingMs: Long? = null,
    /** Round trip to the proxy gateway (through the VPN when chaining). */
    val proxyPingMs: Long? = null,
    /** Times the connection was found not to be working and was rebuilt automatically. */
    val repairs: Int = 0,
) {
    val isActive get() = phase != Phase.DISCONNECTED && phase != Phase.ERROR
}

/** Process-wide tunnel status, written by [ProxyVpnService] and observed by UI / tile. */
object TunnelState {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    internal fun update(block: (TunnelStatus) -> TunnelStatus) = _status.update(block)
}

/** Commands to the service. Callers must have VPN consent (`VpnService.prepare() == null`) before [connect]. */
object TunnelController {
    fun connect(ctx: Context) = send(ctx, ProxyVpnService.ACTION_CONNECT)

    fun disconnect(ctx: Context) {
        if (TunnelState.status.value.isActive) send(ctx, ProxyVpnService.ACTION_DISCONNECT)
    }

    fun rotate(ctx: Context) = send(ctx, ProxyVpnService.ACTION_ROTATE)
    fun recheck(ctx: Context) = send(ctx, ProxyVpnService.ACTION_RECHECK)

    /** Re-targets a running tunnel to the location now in settings; no-op when disconnected. */
    fun applyLocation(ctx: Context) {
        if (TunnelState.status.value.isActive) send(ctx, ProxyVpnService.ACTION_APPLY_LOCATION)
    }

    private fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
        val i = Intent(ctx, ProxyVpnService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(ctx, i)
    }
}
