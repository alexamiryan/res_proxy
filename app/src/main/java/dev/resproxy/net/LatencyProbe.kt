package dev.resproxy.net

import dev.resproxy.data.VpnExit
import dev.resproxy.data.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** A VPN server that can be chosen as exit: [exit] is an [dev.resproxy.data.AppSettings.vpnExit] value. */
data class ProbeTarget(val exit: String, val label: String, val detail: String, val host: String, val countryCode: String?)

data class ProbeState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** exit → round trip in ms, or null when the server didn't answer. */
    val results: Map<String, Long?> = emptyMap(),
    val finishedAt: Long = 0,
)

/**
 * Measures the round trip from this phone to VPN servers with ICMP ping (`/system/bin/ping` works for
 * apps). ResProxy is excluded from its own VPN, so this is the real path to the server, even while connected.
 */
class LatencyProbe {
    private val _state = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = _state.asStateFlow()

    suspend fun run(targets: List<ProbeTarget>, parallel: Int = 32) {
        if (_state.value.running) return
        _state.update { ProbeState(running = true, total = targets.size, results = it.results) }
        val gate = Semaphore(parallel)
        val results = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        try {
            coroutineScope {
                targets.map { t ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val ms = runCatching { ping(Resolver.resolve(t.host).hostAddress!!) }
                                .onFailure { android.util.Log.d("ResProxy", "ping ${t.host} failed", it) }.getOrNull()
                            if (ms != null) results[t.exit] = ms else failed += t.exit
                            _state.update { it.copy(done = it.done + 1) }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            val merged: Map<String, Long?> = failed.associateWith { null as Long? } + results
            _state.update { it.copy(running = false, results = merged, finishedAt = System.currentTimeMillis()) }
        }
    }

    companion object {
        /** One ICMP echo; null on timeout. */
        fun ping(ip: String, timeoutSec: Int = 2): Long? {
            // Android's iputils ping has no -4/-6; IPv6 uses the separate ping6 binary.
            val bin = if (ip.contains(':')) "/system/bin/ping6" else "/system/bin/ping"
            val p = ProcessBuilder(bin, "-c", "1", "-W", timeoutSec.toString(), ip).redirectErrorStream(true).start()
            return try {
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(timeoutSec + 2L, TimeUnit.SECONDS)
                parse(out)
            } finally {
                p.destroy()
            }
        }

        internal fun parse(out: String): Long? =
            Regex("""time[=<]([\d.]+) ?ms""").find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.round(it) }

        fun targets(profiles: List<VpnProfile>, relays: List<MullvadRelay>): List<ProbeTarget> =
            profiles.mapNotNull { p ->
                val cfg = runCatching { WireGuardConfig.parse(p.config) }.getOrNull() ?: return@mapNotNull null
                val peer = cfg.peers.firstOrNull() ?: return@mapNotNull null
                ProbeTarget(VpnExit.imported(p.id), p.name, "My profile · ${cfg.endpoint}", peer.endpointHost, null)
            } + relays.map { r ->
                ProbeTarget(VpnExit.mullvad(r.hostname), "${r.city}, ${r.country}", "Mullvad · ${r.hostname}", r.ipv4, r.countryCode)
            }
    }
}
