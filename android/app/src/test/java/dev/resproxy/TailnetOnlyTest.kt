package dev.resproxy

import dev.resproxy.net.DnsMessage
import dev.resproxy.net.LocalSocksServer
import dev.resproxy.net.TailnetDns
import dev.resproxy.vpn.Routes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** The tailnet-only VPN (proxy off): its routes, and DNS answered by the relay. */
class TailnetOnlyTest {

    private fun query(name: String, type: Int, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in intArrayOf(id, 0x0100, 1, 0, 0, 0)) { out.write(n shr 8); out.write(n and 0xff) }
        name.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
        for (n in intArrayOf(type, 1)) { out.write(n shr 8); out.write(n and 0xff) }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff shl 8) or (b[i + 1].toInt() and 0xff)

    private val forwarded = mutableListOf<String>()
    private val dns = TailnetDns(
        lookup = { name ->
            when (name) {
                "laptop", "laptop.tail1.ts.net" -> listOf(InetAddress.getByName("100.64.0.3"), InetAddress.getByName("fd7a:115c:a1e0::3"))
                "gone.tail1.ts.net" -> emptyList()
                else -> null
            }
        },
        forward = { q ->
            forwarded += DnsMessage.question(q)!!.name
            // A resolver that answers with its own ID.
            q.copyOf().also { it[0] = 0x55; it[1] = 0x66; it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        },
    )

    @Test fun routesOnlyTailscaleRanges() {
        assertEquals(listOf(Routes.Route("100.64.0.0", 10), Routes.Route("fd7a:115c:a1e0::", 48)), Routes.TAILNET)
    }

    @Test fun parsesTheQuestion() {
        val q = DnsMessage.question(query("Laptop.Tail1.ts.net", DnsMessage.TYPE_A))!!
        assertEquals("laptop.tail1.ts.net", q.name)
        assertEquals(DnsMessage.TYPE_A, q.qtype)
        assertEquals(DnsMessage.CLASS_IN, q.qclass)
        // A response, or a truncated message, isn't a query.
        assertNull(DnsMessage.question(query("laptop", 1).also { it[2] = 0x81.toByte() }))
        assertNull(DnsMessage.question(query("laptop", 1).copyOf(14)))
    }

    @Test fun answersTailnetNames() {
        val a = dns.answer(query("laptop", DnsMessage.TYPE_A))!!
        assertEquals(0x1234, u16(a, 0))
        assertEquals(0x8580, u16(a, 2)) // response, authoritative, RD, RA, NOERROR
        assertEquals(1, u16(a, 6)) // one answer
        assertArrayEquals(byteArrayOf(100, 64, 0, 3), a.copyOfRange(a.size - 4, a.size))

        val aaaa = dns.answer(query("laptop.tail1.ts.net", DnsMessage.TYPE_AAAA))!!
        assertEquals(1, u16(aaaa, 6))
        assertArrayEquals(InetAddress.getByName("fd7a:115c:a1e0::3").address, aaaa.copyOfRange(aaaa.size - 16, aaaa.size))

        // Other types: the name exists, no records. Unknown name in the tailnet: NXDOMAIN.
        assertEquals(0, u16(dns.answer(query("laptop", 16))!!, 6))
        val nx = dns.answer(query("gone.tail1.ts.net", DnsMessage.TYPE_A))!!
        assertEquals(3, u16(nx, 2) and 0xf)
        assertEquals(0, u16(nx, 6))
        assertTrue(forwarded.isEmpty())
    }

    @Test fun forwardsEverythingElseWithTheQueryId() {
        val a = dns.answer(query("example.com", DnsMessage.TYPE_A, id = 0x4242))!!
        assertEquals(listOf("example.com"), forwarded)
        assertEquals(0x4242, u16(a, 0))
    }

    @Test fun splitsSocksUdpHeaders() {
        val v4 = byteArrayOf(0, 0, 0, 1, 100, 100, 100, 100, 0, 53, 9, 9)
        val (h, payload) = LocalSocksServer.socksUdpSplit(v4, v4.size)!!
        assertEquals(53, h.port)
        assertArrayEquals(v4.copyOf(10), h.bytes)
        assertArrayEquals(byteArrayOf(9, 9), payload)
        val name = byteArrayOf(0, 0, 0, 3, 3) + "dns".toByteArray() + byteArrayOf(1, 0xbb.toByte(), 7)
        assertEquals(443, LocalSocksServer.socksUdpSplit(name, name.size)!!.first.port)
        assertNull(LocalSocksServer.socksUdpSplit(byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 0, 53), 10)) // fragment
        assertNull(LocalSocksServer.socksUdpSplit(v4, 9)) // cut short
    }

    @Test fun relayAnswersDnsOverUdpAssociate() {
        val relay = LocalSocksServer(null, localDns = dns::answer).also { it.start() }
        try {
            Socket("127.0.0.1", relay.port).use { ctrl ->
                ctrl.soTimeout = 3000
                val out = ctrl.getOutputStream()
                val inp = DataInputStream(ctrl.getInputStream())
                out.write(byteArrayOf(5, 1, 0))
                assertArrayEquals(byteArrayOf(5, 0), ByteArray(2).also { inp.readFully(it) })
                out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                val rep = ByteArray(10).also { inp.readFully(it) }
                assertEquals(0, rep[1].toInt())
                val udpPort = u16(rep, 8)

                DatagramSocket().use { udp ->
                    udp.soTimeout = 3000
                    val header = byteArrayOf(0, 0, 0, 1, 100, 100, 100, 100, 0, 53)
                    val msg = header + query("laptop", DnsMessage.TYPE_A)
                    udp.send(DatagramPacket(msg, msg.size, InetAddress.getByName("127.0.0.1"), udpPort))
                    val buf = ByteArray(1500)
                    val p = DatagramPacket(buf, buf.size)
                    udp.receive(p)
                    val got = buf.copyOf(p.length)
                    assertArrayEquals(header, got.copyOf(10))
                    assertArrayEquals(byteArrayOf(100, 64, 0, 3), got.copyOfRange(got.size - 4, got.size))

                    // Anything but DNS is dropped.
                    val other = byteArrayOf(0, 0, 0, 1, 100, 64, 0, 3, 0, 80, 1, 2, 3)
                    udp.send(DatagramPacket(other, other.size, InetAddress.getByName("127.0.0.1"), udpPort))
                    udp.soTimeout = 500
                    try { udp.receive(p); throw AssertionError("got an answer to a non-DNS datagram") } catch (_: SocketTimeoutException) {}
                }
            }
        } finally {
            relay.stop()
        }
    }
}
