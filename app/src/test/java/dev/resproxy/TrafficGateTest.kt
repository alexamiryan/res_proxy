package dev.resproxy

import dev.resproxy.data.Protocol
import dev.resproxy.net.LanTargets
import dev.resproxy.net.LocalHttpProxy
import dev.resproxy.net.LocalSocksServer
import dev.resproxy.net.Socks5Client
import dev.resproxy.net.TrafficGate
import dev.resproxy.net.Upstream
import dev.resproxy.provider.Endpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class TrafficGateTest {

    private val servers = mutableListOf<ServerSocket>()
    private val proxied = CopyOnWriteArrayList<String>()
    private var echoPort = 0
    private var proxyPort = 0

    @Before fun setUp() {
        echoPort = serve { s ->
            val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return@serve
            s.getOutputStream().write("echo:$line\n".toByteArray())
        }
        // HTTP CONNECT "proxy": records the target, then hands the stream to the echo server.
        proxyPort = serve { s ->
            val inp = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return@serve; head.append(b.toChar()) }
            proxied += head.lines().first()
            val target = Socket("127.0.0.1", echoPort)
            s.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
            thread { runCatching { inp.copyTo(target.getOutputStream()) } }
            runCatching { target.getInputStream().copyTo(s.getOutputStream()) }
        }
    }

    @After fun tearDown() = servers.forEach { it.close() }

    private fun upstream() = Upstream(Endpoint("127.0.0.1", proxyPort, Protocol.HTTP, "u", "p"), timeoutMs = 3000)

    private fun echoVia(port: Int, host: String): String? {
        val s = Socks5Client.connect(Endpoint("127.0.0.1", port, Protocol.SOCKS5, "", ""), host, 443, 3000)
        return s.use {
            it.getOutputStream().write("hi\n".toByteArray())
            BufferedReader(InputStreamReader(it.getInputStream())).readLine()
        }
    }

    @Test fun closedGateRefusesInternetTraffic() {
        val gate = TrafficGate()
        val relay = LocalSocksServer(upstream(), gate = gate, gateWaitMs = 300).start()
        try {
            assertTrue(runCatching { echoVia(relay.port, "example.com") }.isFailure)
            assertTrue(proxied.isEmpty()) // nothing reached the proxy
            gate.open()
            assertEquals("echo:hi", echoVia(relay.port, "example.com"))
            assertEquals(1, proxied.size)
        } finally {
            relay.stop()
        }
    }

    @Test fun connectionWaitsForTheCheckThenGoesThrough() {
        val gate = TrafficGate()
        val relay = LocalSocksServer(upstream(), gate = gate, gateWaitMs = 5000).start()
        try {
            thread { Thread.sleep(300); gate.open() }
            assertEquals("echo:hi", echoVia(relay.port, "example.com"))
        } finally {
            relay.stop()
        }
    }

    @Test fun closingConnectionsDropsOpenOnes() {
        val gate = TrafficGate().apply { open() }
        val relay = LocalSocksServer(upstream(), gate = gate).start()
        try {
            val s = Socks5Client.connect(Endpoint("127.0.0.1", relay.port, Protocol.SOCKS5, "", ""), "example.com", 443, 3000)
            Thread.sleep(200)
            relay.closeConnections()
            s.soTimeout = 2000
            val r = runCatching { s.getInputStream().read() }
            assertTrue(r.isFailure || r.getOrNull() == -1)
            s.close()
        } finally {
            relay.stop()
        }
    }

    @Test fun lanTargetsBypassTheProxyEvenWhileBlocked() {
        val relay = LocalSocksServer(
            upstream(), gate = TrafficGate(), gateWaitMs = 300, lanDirect = { true },
            lanResolve = { if (it == "nas.lan") InetAddress.getByName("127.0.0.1") else null },
        ).start()
        try {
            val s = Socks5Client.connect(Endpoint("127.0.0.1", relay.port, Protocol.SOCKS5, "", ""), "nas.lan", echoPort, 3000)
            s.use {
                it.getOutputStream().write("hi\n".toByteArray())
                assertEquals("echo:hi", BufferedReader(InputStreamReader(it.getInputStream())).readLine())
            }
            assertTrue(proxied.isEmpty())
        } finally {
            relay.stop()
        }
    }

    @Test fun blockedSystemProxyAnswers503() {
        val proxy = LocalHttpProxy(upstream(), TrafficGate()).start()
        try {
            // A closed gate waits ~8 s for a check before answering; keep the test bounded.
            Socket("127.0.0.1", proxy.port).use { s ->
                s.soTimeout = 15_000
                s.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
                assertTrue(BufferedReader(InputStreamReader(s.getInputStream())).readLine().contains("503"))
            }
            assertTrue(proxied.isEmpty())
        } finally {
            proxy.stop()
        }
    }

    @Test fun lanTargetDetection() {
        assertTrue(LanTargets.isLocalName("nas"))
        assertTrue(LanTargets.isLocalName("printer.local"))
        assertTrue(LanTargets.isLocalName("router.home.arpa."))
        assertFalse(LanTargets.isLocalName("example.com"))
        assertFalse(LanTargets.isLocalName("192.168.1.1"))
        assertFalse(LanTargets.isLocalName("localhost"))

        assertEquals("192.168.1.1", LanTargets.resolve("192.168.1.1")?.hostAddress)
        assertEquals("10.0.0.5", LanTargets.resolve("10.0.0.5")?.hostAddress)
        assertTrue(LanTargets.resolve("fd0c:a635::1") != null)
        assertTrue(LanTargets.resolve("fe80::1") != null)
        assertNull(LanTargets.resolve("8.8.8.8"))
        assertNull(LanTargets.resolve("100.64.0.3")) // hev fake-IP range, not LAN
        assertNull(LanTargets.resolve("2001:db8::1"))

        val lookups = mutableListOf<String>()
        val fake = { h: String -> lookups += h; arrayOf(InetAddress.getByName(if (h == "nas") "192.168.1.20" else "93.184.216.34")) }
        assertEquals("192.168.1.20", LanTargets.resolve("nas", fake)?.hostAddress)
        assertNull(LanTargets.resolve("evil.local", fake)) // local name that resolves publicly: go through the proxy
        assertNull(LanTargets.resolve("example.com", fake))
        assertEquals(listOf("nas", "evil.local"), lookups) // public names are never resolved locally (no DNS leak)
    }

    private fun serve(handler: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { c.use { runCatching { handler(it) } } }
            }
        }
        return ss.localPort
    }
}
