package dev.resproxy.net

import java.io.IOException
import java.util.Base64

/** A parsed wg-quick style `.conf` (the format VPN providers such as Proton export). */
data class WireGuardConfig(
    val privateKey: ByteArray,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val peers: List<Peer>,
) {
    data class Peer(
        val publicKey: ByteArray,
        val presharedKey: ByteArray?,
        val endpointHost: String,
        val endpointPort: Int,
        val allowedIps: List<String>,
        val keepalive: Int,
    )

    val endpoint: String get() = peers.firstOrNull()?.let { "${it.endpointHost}:${it.endpointPort}" }.orEmpty()

    /** wireguard-go UAPI; [resolve] turns endpoint hostnames into IP literals. */
    fun toUapi(resolve: (String) -> String): String = buildString {
        appendLine("private_key=${hex(privateKey)}")
        appendLine("replace_peers=true")
        for (p in peers) {
            appendLine("public_key=${hex(p.publicKey)}")
            p.presharedKey?.let { appendLine("preshared_key=${hex(it)}") }
            val ip = resolve(p.endpointHost)
            appendLine("endpoint=${if (ip.contains(':')) "[$ip]" else ip}:${p.endpointPort}")
            appendLine("persistent_keepalive_interval=${p.keepalive}")
            appendLine("replace_allowed_ips=true")
            p.allowedIps.forEach { appendLine("allowed_ip=$it") }
        }
    }

    companion object {
        fun parse(text: String): WireGuardConfig {
            var section = ""
            var privateKey: ByteArray? = null
            val addresses = mutableListOf<String>()
            val dns = mutableListOf<String>()
            var mtu = 1280
            val peers = mutableListOf<Peer>()
            var peer: MutableMap<String, String>? = null

            fun flushPeer() {
                val m = peer ?: return
                val pub = m["publickey"] ?: throw IOException("[Peer] has no PublicKey")
                val ep = m["endpoint"] ?: throw IOException("[Peer] has no Endpoint")
                val i = ep.lastIndexOf(':')
                if (i <= 0) throw IOException("Bad Endpoint: $ep")
                peers += Peer(
                    publicKey = key(pub),
                    presharedKey = m["presharedkey"]?.let(::key),
                    endpointHost = ep.substring(0, i).trim('[', ']'),
                    endpointPort = ep.substring(i + 1).toIntOrNull() ?: throw IOException("Bad Endpoint port: $ep"),
                    allowedIps = (m["allowedips"] ?: "0.0.0.0/0, ::/0").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    keepalive = m["persistentkeepalive"]?.toIntOrNull() ?: 25,
                )
                peer = null
            }

            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[")) {
                    flushPeer()
                    section = line.lowercase()
                    if (section == "[peer]") peer = mutableMapOf()
                    continue
                }
                val k = line.substringBefore('=').trim().lowercase()
                val v = line.substringAfter('=', "").trim()
                when (section) {
                    "[interface]" -> when (k) {
                        "privatekey" -> privateKey = key(v)
                        "address" -> addresses += v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        "dns" -> dns += v.split(',').map { it.trim() }.filter { isIpv4Literal(it) || it.contains(':') }
                        "mtu" -> mtu = v.toIntOrNull() ?: mtu
                    }
                    "[peer]" -> peer?.put(k, v)
                }
            }
            flushPeer()
            return WireGuardConfig(
                privateKey = privateKey ?: throw IOException("[Interface] has no PrivateKey"),
                addresses = addresses.ifEmpty { throw IOException("[Interface] has no Address") },
                dns = dns,
                mtu = mtu.coerceIn(1280, 1420),
                peers = peers.ifEmpty { throw IOException("Config has no [Peer]") },
            )
        }

        private fun key(b64: String): ByteArray {
            val k = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()
            if (k == null || k.size != 32) throw IOException("Invalid WireGuard key")
            return k
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Userspace WireGuard from libwgbridge.so (wireguard-go + netstack). It runs in-process and never
 * touches Android's VpnService, so ResProxy can chain: apps → TUN → WireGuard → residential proxy.
 */
object WireGuard {
    val available: Boolean by lazy { runCatching { System.loadLibrary("wgbridge") }.isSuccess }

    @Volatile var running = false
        private set

    fun start(config: WireGuardConfig) {
        if (!available) throw IOException("WireGuard isn't included in this build")
        val uapi = config.toUapi { host -> Resolver.resolve(host).hostAddress ?: host }
        val err = nativeStart(uapi, config.addresses.joinToString(","), config.dns.joinToString(","), config.mtu)
        if (err.isNotEmpty()) throw IOException("WireGuard: $err")
        running = true
    }

    /** Local port relaying TCP to [host]:[port] through the tunnel (DNS resolved inside the VPN). */
    fun forward(host: String, port: Int): Int {
        val target = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        val p = nativeForward(target)
        if (p <= 0) throw IOException("WireGuard is not running")
        return p
    }

    fun stop() {
        if (available && running) nativeStop()
        running = false
    }

    /** Unix time (s) of the last handshake, or null if none yet. */
    fun handshakeAt(): Long? {
        if (!running) return null
        return nativeStatus().split(',').firstOrNull()?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** Seconds since the last handshake, or null if none yet. */
    fun handshakeAgeSec(): Long? = handshakeAt()?.let { System.currentTimeMillis() / 1000 - it }

    /** In-tunnel ICMP round trip to [ip] (e.g. the VPN's DNS server, which sits on the VPN server) in ms. */
    fun ping(ip: String): Long? {
        if (!running) return null
        return nativePing(ip).takeIf { it >= 0 }?.toLong()
    }

    @JvmStatic private external fun nativePing(target: String): Int
    @JvmStatic private external fun nativeStart(uapi: String, addresses: String, dns: String, mtu: Int): String
    @JvmStatic private external fun nativeForward(target: String): Int
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeStatus(): String
}
