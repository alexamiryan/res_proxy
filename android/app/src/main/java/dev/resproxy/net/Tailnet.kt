package dev.resproxy.net

import dev.resproxy.data.Protocol
import dev.resproxy.provider.Endpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * How the relays send tailnet targets around the residential proxy. Asked for every app connection before the traffic
 * gate, the LAN check and the proxy: tailnet traffic is not internet traffic and never uses the exit IP.
 */
interface TailnetRoute {
    /** Whether [host] (IP literal or name) is a tailnet target. */
    fun matches(host: String): Boolean
    /** Connects through the tailnet; a [SocksRefusedException] / IOException when the device can't be reached. */
    fun open(host: String, port: Int): Socket
}

/** Tailnet node status, as reported by the Go side. */
@Serializable
data class TailnetStatus(
    val state: String = "",
    val authUrl: String = "",
    val ips: List<String> = emptyList(),
    val dnsName: String = "",
    val magicDnsSuffix: String = "",
    val keyExpiry: Long = 0,
    val peers: List<TailnetPeer> = emptyList(),
    val socksPort: Int = 0,
    val error: String = "",
    val inboundConns: Long = 0,
    val outboundConns: Long = 0,
) {
    val running: Boolean get() = state == "Running"
    val needsLogin: Boolean get() = state == "NeedsLogin" || state == "NeedsMachineAuth"
}

@Serializable
data class TailnetPeer(
    val hostname: String = "",
    val dnsName: String = "",
    val ips: List<String> = emptyList(),
    val online: Boolean = false,
    val os: String = "",
) {
    /** The name to type on the phone: the first label of the MagicDNS name. */
    val shortName: String get() = dnsName.substringBefore('.').ifEmpty { hostname }
}

/**
 * ResProxy's own Tailscale node (tsnet in libwgbridge.so: userspace, no TUN), so the phone is in the tailnet without the
 * Tailscale app. Outbound: the relay hands tailnet targets to the node's SOCKS5 front, which only dials peers. Inbound:
 * peers' TCP connections go to local listeners. Its lifecycle is separate from the proxy tunnel's (see
 * [dev.resproxy.vpn.TailnetController]).
 */
object Tailnet : TailnetRoute {
    val available: Boolean get() = WireGuard.available

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    @Volatile private var socks: Pair<Int, String>? = null

    @Volatile var running = false
        private set

    /** Starts the node; returns an error message or null. */
    fun start(
        dir: String, hostname: String, authKey: String, controlUrl: String, stateKeyHex: String,
        localAddr: String, loopbackPorts: Collection<Int>,
    ): String? {
        if (!available) return "Tailnet isn't included in this build"
        val err = nativeStart(dir, hostname, authKey, controlUrl, stateKeyHex, localAddr, loopbackPorts.joinToString(","))
        if (err.isNotEmpty()) { running = false; socks = null; return err }
        socks = nativeSocks().split(':', limit = 2).takeIf { it.size == 2 }?.let { (p, pw) -> p.toInt() to pw }
        running = true
        return null
    }

    fun stop() {
        if (available && running) nativeStop()
        running = false
        socks = null
    }

    /** Removes this device from the tailnet and stops the node; returns an error message or null. */
    fun logout(): String? {
        if (!available) return null
        val err = nativeLogout()
        running = false
        socks = null
        return err.ifEmpty { null }
    }

    fun status(): TailnetStatus? {
        if (!available || !running) return null
        val s = nativeStatus()
        if (s.isEmpty()) return null
        return runCatching { json.decodeFromString(TailnetStatus.serializer(), s) }.getOrNull()
    }

    override fun matches(host: String): Boolean = running && nativeIsTailnetHost(host)

    override fun open(host: String, port: Int): Socket {
        val (p, pw) = socks ?: throw SocksRefusedException("Tailnet is not running", 4)
        return Socks5Client.connect(Endpoint("127.0.0.1", p, Protocol.SOCKS5, SOCKS_USER, pw), host, port, 15_000)
    }

    /** Reports the phone's interfaces (Go can't list them on Android 11+), leaving out the VPN's own TUN. */
    fun reportInterfaces(tunAddresses: Set<String>) {
        if (!available) return
        val list = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        nativeSetInterfaces(interfaceSpec(list.mapNotNull { nif ->
            runCatching {
                Iface(
                    nif.name, nif.index, nif.mtu, nif.isUp, nif.isLoopback, nif.isPointToPoint, nif.supportsMulticast(),
                    nif.interfaceAddresses.mapNotNull { a -> a.address?.let { it to a.networkPrefixLength.toInt() } },
                )
            }.getOrNull()
        }, tunAddresses))
    }

    fun networkChanged() {
        if (available && running) nativeNetworkChanged()
    }

    data class Iface(
        val name: String, val index: Int, val mtu: Int, val up: Boolean, val loopback: Boolean, val p2p: Boolean,
        val multicast: Boolean, val addresses: List<Pair<InetAddress, Int>>,
    )

    /** One line per interface: `name index mtu flags addr/prefix…` (see SetInterfaces in internal/tailnet). */
    internal fun interfaceSpec(list: List<Iface>, tunAddresses: Set<String>): String = list
        .filter { i -> i.addresses.none { (a, _) -> a.hostAddress?.substringBefore('%') in tunAddresses } }
        .joinToString("\n") { i ->
            val flags = buildString {
                if (i.up) append('u'); if (i.loopback) append('l'); if (i.p2p) append('p'); if (i.multicast) append('m')
            }.ifEmpty { "-" }
            val addrs = i.addresses.mapNotNull { (a, prefix) ->
                when (a) {
                    is Inet4Address, is Inet6Address -> "${a.hostAddress!!.substringBefore('%')}/$prefix"
                    else -> null
                }
            }
            (listOf(i.name, i.index.toString(), i.mtu.toString(), flags) + addrs).joinToString(" ")
        }

    /** Tailscale's address ranges: 100.64.0.0/10 and fd7a:115c:a1e0::/48. Only IP literals; names need the node. */
    fun inRange(host: String): Boolean {
        val h = host.removeSurrounding("[", "]")
        if (!LanTargets.isIpLiteral(h)) return false
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        val b = a.address
        return when (a) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            is Inet6Address -> b.size == 16 && b[0] == 0xfd.toByte() && b[1] == 0x7a.toByte() && b[2] == 0x11.toByte() &&
                b[3] == 0x5c.toByte() && b[4] == 0xa1.toByte() && b[5] == 0xe0.toByte()
            else -> false
        }
    }

    /** While the tailnet is on but not (yet) running: tailnet addresses fail fast instead of going to the proxy. */
    object Down : TailnetRoute {
        override fun matches(host: String) = inRange(host)
        override fun open(host: String, port: Int): Socket = throw SocksRefusedException("Tailnet is not connected", 4)
    }

    const val SOCKS_USER = "tailnet"

    @JvmStatic private external fun nativeStart(
        dir: String, hostname: String, authKey: String, controlUrl: String, stateKey: String, localAddr: String, loopbackPorts: String,
    ): String
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeLogout(): String
    @JvmStatic private external fun nativeStatus(): String
    @JvmStatic private external fun nativeIsTailnetHost(host: String): Boolean
    @JvmStatic private external fun nativeSocks(): String
    @JvmStatic private external fun nativeSetInterfaces(spec: String)
    @JvmStatic private external fun nativeNetworkChanged()
}
