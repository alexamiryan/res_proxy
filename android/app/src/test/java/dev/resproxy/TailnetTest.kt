package dev.resproxy

import dev.resproxy.data.AppSettings
import dev.resproxy.data.Protocol
import dev.resproxy.data.TailnetSettings
import dev.resproxy.net.LocalHttpProxy
import dev.resproxy.net.LocalSocksServer
import dev.resproxy.net.RelayHealth
import dev.resproxy.net.Socks5Client
import dev.resproxy.net.SocksRefusedException
import dev.resproxy.net.Tailnet
import dev.resproxy.net.TailnetRoute
import dev.resproxy.net.TrafficGate
import dev.resproxy.net.Upstream
import dev.resproxy.provider.Endpoint
import dev.resproxy.ui.parsePorts
import dev.resproxy.vpn.TailnetController
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** The relays' tailnet branch: tailnet targets skip the gate, the LAN check and the residential proxy. */
class TailnetTest {

    private val servers = mutableListOf<ServerSocket>()
    private val proxied = CopyOnWriteArrayList<String>()
    private val viaTailnet = CopyOnWriteArrayList<String>()
    private var echoPort = 0
    private var proxyPort = 0

    @Before fun setUp() {
        echoPort = serve { s ->
            val r = BufferedReader(InputStreamReader(s.getInputStream()))
            while (true) {
                val line = r.readLine() ?: return@serve
                s.getOutputStream().write("echo:$line\n".toByteArray())
            }
        }
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

    private fun serve(handler: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { runCatching { handler(it) } } }
            }
        }
        return ss.localPort
    }

    private fun upstream() = Upstream(Endpoint("127.0.0.1", proxyPort, Protocol.HTTP, "u", "p"), timeoutMs = 3000)

    /** A tailnet with one device, `laptop`, that answers like the echo server. */
    private val route = object : TailnetRoute {
        override fun matches(host: String) = host == "laptop" || host.endsWith(".tail1234.ts.net") || Tailnet.inRange(host)
        override fun open(host: String, port: Int): Socket {
            if (host != "laptop" && host != "100.101.1.2") throw SocksRefusedException("not a device", 4)
            viaTailnet += "$host:$port"
            return Socket("127.0.0.1", echoPort)
        }
    }

    private fun socks(port: Int, host: String) = Socks5Client.connect(Endpoint("127.0.0.1", port, Protocol.SOCKS5, "", ""), host, 22, 3000)

    private fun Socket.say(text: String): String? {
        getOutputStream().write("$text\n".toByteArray())
        soTimeout = 3000
        return BufferedReader(InputStreamReader(getInputStream())).readLine()
    }

    @Test fun tailnetTargetsSkipTheGateTheLanCheckAndTheProxy() {
        val health = RelayHealth()
        val up = AtomicLong(); val down = AtomicLong()
        val relay = LocalSocksServer(
            upstream(), health, gate = TrafficGate(), gateWaitMs = 300, lanDirect = { true },
            // `laptop` is a bare name, which the LAN check would otherwise claim.
            lanResolve = { if (it == "laptop") InetAddress.getByName("127.0.0.1") else null },
            tailnet = { route }, tailnetUp = up, tailnetDown = down,
        ).start()
        try {
            socks(relay.port, "laptop").use { assertEquals("echo:hi", it.say("hi")) }
            socks(relay.port, "100.101.1.2").use { assertEquals("echo:hey", it.say("hey")) }
            assertEquals(listOf("laptop:22", "100.101.1.2:22"), viaTailnet)
            assertTrue("nothing went to the proxy", proxied.isEmpty())
            // Not proxy traffic: neither relay health nor proxy usage.
            Thread.sleep(200)
            assertEquals(0L, health.ok.get()); assertEquals(0L, health.failed.get())
            assertEquals(0L, relay.bytesUp.get()); assertEquals(0L, relay.bytesDown.get())
            assertTrue(up.get() >= 6 && down.get() >= 12)
            // Internet targets still wait for the gate (closed here).
            assertTrue(runCatching { socks(relay.port, "example.com").say("x") }.isFailure)
            assertTrue(proxied.isEmpty())
        } finally {
            relay.stop()
        }
    }

    @Test fun unknownTailnetTargetsFailFastInsteadOfGoingToTheProxy() {
        val relay = LocalSocksServer(upstream(), gate = TrafficGate().apply { open() }, tailnet = { route }).start()
        try {
            for (host in listOf("100.64.0.9", "printer.tail1234.ts.net")) {
                val e = runCatching { socks(relay.port, host) }.exceptionOrNull()
                assertEquals(host, 4, (e as SocksRefusedException).code)
            }
            // Same while the tailnet is on but not running yet.
            val down = LocalSocksServer(upstream(), gate = TrafficGate().apply { open() }, tailnet = { Tailnet.Down }).start()
            try {
                assertEquals(4, (runCatching { socks(down.port, "100.101.1.2") }.exceptionOrNull() as SocksRefusedException).code)
                // Names need the node: with the node down they go the normal way.
                socks(down.port, "example.com").use { assertEquals("echo:x", it.say("x")) }
            } finally {
                down.stop()
            }
            assertEquals(1, proxied.size)
        } finally {
            relay.stop()
        }
    }

    @Test fun withTheTailnetOffEverythingGoesToTheProxy() {
        val relay = LocalSocksServer(upstream(), gate = TrafficGate().apply { open() }, tailnet = { null }).start()
        try {
            socks(relay.port, "100.101.1.2").use { assertEquals("echo:a", it.say("a")) }
            assertEquals(1, proxied.size)
            assertTrue(viaTailnet.isEmpty())
        } finally {
            relay.stop()
        }
    }

    @Test fun newExitIpDropsProxyConnectionsButNotTailnetOnes() {
        val relay = LocalSocksServer(upstream(), gate = TrafficGate().apply { open() }, tailnet = { route }).start()
        try {
            val ssh = socks(relay.port, "laptop")
            val web = socks(relay.port, "example.com")
            assertEquals("echo:1", ssh.say("1")); assertEquals("echo:2", web.say("2"))
            relay.closeConnections()
            Thread.sleep(200)
            assertEquals("echo:still here", ssh.say("still here"))
            web.soTimeout = 2000
            val r = runCatching { web.getInputStream().read() }
            assertTrue(r.isFailure || r.getOrNull() == -1)
            ssh.close(); web.close()
        } finally {
            relay.stop()
        }
    }

    @Test fun stoppingTheRelayClosesTailnetConnectionsToo() {
        val relay = LocalSocksServer(upstream(), gate = TrafficGate().apply { open() }, tailnet = { route }).start()
        val ssh = socks(relay.port, "laptop")
        assertEquals("echo:1", ssh.say("1"))
        relay.stop()
        ssh.soTimeout = 2000
        val r = runCatching { ssh.getInputStream().read() }
        assertTrue(r.isFailure || r.getOrNull() == -1)
        ssh.close()
    }

    @Test fun proxyOnlyModeSendsTailnetConnectsAroundTheGate() {
        val up = AtomicLong(); val down = AtomicLong()
        val proxy = LocalHttpProxy(upstream(), TrafficGate(), { route }, up, down).start()
        try {
            Socket("127.0.0.1", proxy.port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("CONNECT laptop:22 HTTP/1.1\r\nHost: laptop:22\r\n\r\n".toByteArray())
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                assertEquals("HTTP/1.1 200 Connection established", r.readLine())
                r.readLine()
                s.getOutputStream().write("ping\n".toByteArray())
                assertEquals("echo:ping", r.readLine())
            }
            assertTrue(proxied.isEmpty())
            assertEquals(0L, proxy.bytesUp.get())
            assertTrue(up.get() > 0)
            Socket("127.0.0.1", proxy.port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("CONNECT 100.64.0.9:22 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(BufferedReader(InputStreamReader(s.getInputStream())).readLine()!!.startsWith("HTTP/1.1 502"))
            }
        } finally {
            proxy.stop()
        }
    }

    @Test fun tailnetRanges() {
        for (h in listOf("100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1", "[fd7a:115c:a1e0:ab12::7]")) assertTrue(h, Tailnet.inRange(h))
        for (h in listOf("100.63.255.255", "100.128.0.1", "198.19.0.1", "fd00::1", "laptop", "100.64.example.com", "")) assertFalse(h, Tailnet.inRange(h))
    }

    @Test fun interfaceListLeavesOutTheVpnTun() {
        fun a(s: String) = InetAddress.getByName(s)
        val spec = Tailnet.interfaceSpec(
            listOf(
                Tailnet.Iface("wlan0", 30, 1500, up = true, loopback = false, p2p = false, multicast = true,
                    addresses = listOf(a("192.168.1.20") to 24, a("fe80::1%1") to 64)),
                Tailnet.Iface("tun0", 40, 8500, up = true, loopback = false, p2p = true, multicast = false,
                    addresses = listOf(a("198.18.0.1") to 32)),
                Tailnet.Iface("lo", 1, 65536, up = true, loopback = true, p2p = false, multicast = false, addresses = listOf(a("127.0.0.1") to 8)),
            ),
            setOf("198.18.0.1", "fc00::1"),
        )
        assertEquals("wlan0 30 1500 um 192.168.1.20/24 fe80:0:0:0:0:0:0:1/64\nlo 1 65536 ul 127.0.0.1/8", spec)
    }

    @Test fun defaultDeviceNames() {
        assertEquals("resproxy-pixel-8-pro", TailnetController.defaultHostname("Pixel 8 Pro", 0))
        assertEquals("resproxy-sm-s918b-u10", TailnetController.defaultHostname("SM-S918B", 10))
        assertEquals("resproxy-android", TailnetController.defaultHostname("???", 0))
    }

    @Test fun portsField() {
        assertEquals(setOf(22, 8022, 8384), parsePorts("8022, 22 8384,,0, 70000"))
        assertEquals(emptySet<Int>(), parsePorts(""))
    }

    @Test fun settingsDefaultsAndRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // Old settings without the field get the defaults.
        val old = json.decodeFromString(AppSettings.serializer(), """{"schema":3}""")
        assertEquals(TailnetSettings(), old.tailnet)
        assertFalse(old.tailnet.enabled)
        assertTrue(old.tailnet.acceptInbound)
        val s = AppSettings(tailnet = TailnetSettings(enabled = true, hostname = "phone", controlUrl = "https://hs.example", acceptInbound = false, loopbackPorts = setOf(8022)))
        val text = json.encodeToString(AppSettings.serializer(), s)
        assertTrue(text.contains("\"tailnet\":{\"enabled\":true,\"hostname\":\"phone\",\"controlUrl\":\"https://hs.example\",\"acceptInbound\":false,\"loopbackPorts\":[8022]}"))
        assertEquals(s, json.decodeFromString(AppSettings.serializer(), text))
    }
}
