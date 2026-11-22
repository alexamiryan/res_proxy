package dev.resproxy.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * DNS for the tailnet-only VPN (proxy off): the TUN's DNS server is [Tailnet.DNS], and the relay hands every query here.
 * Tailnet names (`laptop`, `laptop.tail1234.ts.net`) are answered from the node; everything else goes to the network's
 * own resolver ([forward]), so with the proxy off the rest of DNS is what it would be without ResProxy.
 */
class TailnetDns(
    /** null: not a tailnet name. Empty: a tailnet name no device has (NXDOMAIN). */
    private val lookup: (String) -> List<InetAddress>?,
    private val forward: (ByteArray) -> ByteArray?,
) {
    fun answer(query: ByteArray): ByteArray? {
        val q = DnsMessage.question(query) ?: return null
        val ips = if (q.qclass == DnsMessage.CLASS_IN) lookup(q.name) else null
        if (ips == null) return forward(query)?.let { DnsMessage.withId(it, query) }
        val records = when (q.qtype) {
            DnsMessage.TYPE_A -> ips.filterIsInstance<Inet4Address>()
            DnsMessage.TYPE_AAAA -> ips.filterIsInstance<Inet6Address>()
            else -> emptyList()
        }
        return DnsMessage.response(query, q, records, nxdomain = ips.isEmpty())
    }
}

/** Just enough DNS wire format for one-question queries and A / AAAA answers. */
internal object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1
    private const val TTL = 600

    data class Question(val name: String, val qtype: Int, val qclass: Int, val end: Int)

    /** The single question of a standard query, or null for anything else. */
    fun question(m: ByteArray): Question? {
        if (m.size < 12 || m[2].toInt() and 0x80 != 0 || u16(m, 4) != 1) return null
        var i = 12
        val labels = mutableListOf<String>()
        while (true) {
            if (i >= m.size) return null
            val len = m[i].toInt() and 0xff
            if (len == 0) { i++; break }
            if (len and 0xc0 != 0 || i + 1 + len > m.size) return null
            labels += String(m, i + 1, len, Charsets.ISO_8859_1)
            i += 1 + len
        }
        if (i + 4 > m.size) return null
        return Question(labels.joinToString(".").lowercase(), u16(m, i), u16(m, i + 2), i + 4)
    }

    /** An answer to [query] with [records] (none: NOERROR / NODATA, or NXDOMAIN). */
    fun response(query: ByteArray, q: Question, records: List<InetAddress>, nxdomain: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write(0x84 or (query[2].toInt() and 0x01)) // response, authoritative, recursion desired as asked
        out.write(0x80 or (if (nxdomain) 3 else 0)) // recursion available, rcode
        for (n in intArrayOf(1, records.size, 0, 0)) { out.write(n shr 8); out.write(n and 0xff) }
        out.write(query, 12, q.end - 12)
        for (a in records) {
            val rdata = a.address
            out.write(0xc0); out.write(12) // the name: pointer to the question
            val type = if (rdata.size == 4) TYPE_A else TYPE_AAAA
            for (n in intArrayOf(type, CLASS_IN, TTL shr 16, TTL and 0xffff, rdata.size)) { out.write(n shr 8); out.write(n and 0xff) }
            out.write(rdata)
        }
        return out.toByteArray()
    }

    /** [answer] with the ID of [query], in case the resolver used its own. */
    fun withId(answer: ByteArray, query: ByteArray): ByteArray {
        if (answer.size < 2) return answer
        return answer.copyOf().also { it[0] = query[0]; it[1] = query[1] }
    }

    private fun u16(m: ByteArray, i: Int) = (m[i].toInt() and 0xff shl 8) or (m[i + 1].toInt() and 0xff)
}

/**
 * The network's own resolver, for names that aren't the tailnet's. ResProxy is outside its own VPN, so this is the
 * Wi-Fi / mobile network's DNS (with Private DNS where Android uses it).
 */
object SystemDns {
    fun query(ctx: Context, query: ByteArray, timeoutMs: Long = 5_000): ByteArray? =
        if (Build.VERSION.SDK_INT >= 29) rawQuery(query, timeoutMs) else udpQuery(ctx, query, timeoutMs)

    private fun rawQuery(query: ByteArray, timeoutMs: Long): ByteArray? {
        if (Build.VERSION.SDK_INT < 29) return null
        val result = AtomicReference<ByteArray?>()
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        DnsResolver.getInstance().rawQuery(null, query, DnsResolver.FLAG_EMPTY, Runnable::run, cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) { result.set(answer); done.countDown() }
                override fun onError(error: DnsResolver.DnsException) { done.countDown() }
            })
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
        return result.get()
    }

    private fun udpQuery(ctx: Context, query: ByteArray, timeoutMs: Long): ByteArray? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val servers = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
        for (server in servers) {
            runCatching {
                DatagramSocket().use { s ->
                    s.soTimeout = timeoutMs.toInt()
                    s.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    return buf.copyOf(p.length)
                }
            }
        }
        return null
    }
}
