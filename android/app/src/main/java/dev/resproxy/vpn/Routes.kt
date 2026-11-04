package dev.resproxy.vpn

import java.math.BigInteger
import java.net.InetAddress

/** TUN routes. With LAN bypass, "everything" minus private/link-local/multicast ranges. */
object Routes {
    data class Route(val address: String, val prefix: Int)

    /** Kept off the VPN with LAN bypass on. 100.64.0.0/10 is deliberately routed: hev's fake-IP DNS uses it. */
    val LAN_V4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255/32")
    val LAN_V6 = listOf("fe80::/10", "fd00::/8", "ff00::/8")

    fun v4(bypassLan: Boolean) = if (bypassLan) complement(LAN_V4, 32) else listOf(Route("0.0.0.0", 0))
    fun v6(bypassLan: Boolean) = if (bypassLan) complement(LAN_V6, 128) else listOf(Route("::", 0))

    /** Smallest set of prefixes covering the whole address space except [excluded]. */
    fun complement(excluded: List<String>, bits: Int): List<Route> {
        val ex = excluded.map { parse(it) }
        val out = mutableListOf<Route>()
        fun walk(base: BigInteger, len: Int) {
            val size = BigInteger.ONE.shiftLeft(bits - len)
            val end = base + size
            var overlaps = false
            for ((eBase, eLen) in ex) {
                val eEnd = eBase + BigInteger.ONE.shiftLeft(bits - eLen)
                if (eBase <= base && end <= eEnd) return // fully excluded
                if (eBase < end && base < eEnd) overlaps = true
            }
            if (!overlaps) { out += Route(format(base, bits), len); return }
            walk(base, len + 1)
            walk(base + BigInteger.ONE.shiftLeft(bits - len - 1), len + 1)
        }
        walk(BigInteger.ZERO, 0)
        return out
    }

    private fun parse(cidr: String): Pair<BigInteger, Int> {
        val (a, p) = cidr.split('/')
        return BigInteger(1, InetAddress.getByName(a).address) to p.toInt()
    }

    private fun format(v: BigInteger, bits: Int): String {
        val raw = v.toByteArray().let { if (it.size > bits / 8) it.copyOfRange(it.size - bits / 8, it.size) else it }
        return InetAddress.getByAddress(ByteArray(bits / 8 - raw.size) + raw).hostAddress!!
    }
}
