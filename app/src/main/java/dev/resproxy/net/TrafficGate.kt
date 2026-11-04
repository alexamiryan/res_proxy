package dev.resproxy.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Lets apps' internet traffic through only while open, i.e. while the current exit IP has passed the
 * reputation check. The app's own checks use a separate, ungated local proxy.
 */
class TrafficGate {
    private val lock = Object()
    @Volatile var isOpen = false
        private set

    fun open() = synchronized(lock) { isOpen = true; lock.notifyAll() }

    fun close() { isOpen = false }

    /** Waits up to [timeoutMs] for the gate to open; a connection that starts during a check gets the clean IP. */
    fun await(timeoutMs: Long): Boolean {
        if (isOpen) return true
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!isOpen) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
        }
        return true
    }
}

/** Local-network targets that the relay connects to directly (LAN bypass) instead of through the proxy. */
object LanTargets {
    private val SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".fritz.box")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun isIpLiteral(host: String) = IPV4.matches(host) || ':' in host

    /** Names that only the local network can resolve: single labels (`nas`) and local suffixes (`printer.local`). */
    fun isLocalName(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || isIpLiteral(h) || h == "localhost") return false
        return '.' !in h || SUFFIXES.any { h.endsWith(it) }
    }

    /** RFC 1918, link-local and IPv6 unique-local addresses. */
    fun isPrivate(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> a.isSiteLocalAddress || a.isLinkLocalAddress
        is Inet6Address -> a.isLinkLocalAddress || (a.address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }

    /**
     * The LAN address to connect to directly, or null to go through the proxy. Local names are looked up with
     * the network's own DNS (this app is outside the VPN); public names are never resolved here, so no DNS leak.
     */
    fun resolve(host: String, lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName): InetAddress? = when {
        isIpLiteral(host) -> runCatching { InetAddress.getByName(host.removeSurrounding("[", "]")) }.getOrNull()?.takeIf(::isPrivate)
        isLocalName(host) -> runCatching { lookup(host) }.getOrNull()?.firstOrNull(::isPrivate)
        else -> null
    }
}
