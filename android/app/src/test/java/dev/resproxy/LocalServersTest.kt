package dev.resproxy

import dev.resproxy.data.Protocol
import dev.resproxy.net.LocalHttpProxy
import dev.resproxy.net.LocalSocksServer
import dev.resproxy.net.ProxyAuthException
import dev.resproxy.net.Socks5Client
import dev.resproxy.net.Upstream
import dev.resproxy.provider.Endpoint
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Fake upstream HTTP CONNECT proxy (auth required) + a target that echoes the first line it
 * receives, so the whole local chain can be exercised on the JVM.
 */
class LocalServersTest {

    private val servers = mutableListOf<ServerSocket>()
    private val seenConnects = CopyOnWriteArrayList<String>()
    private val seenRequests = CopyOnWriteArrayList<String>()
    private var echoPort = 0
    private var proxyPort = 0

    @Before fun setUp() {
        echoPort = serve { s ->
            val r = BufferedReader(InputStreamReader(s.getInputStream()))
            val first = r.readLine() ?: return@serve
            seenRequests += first
            s.getOutputStream().write("echo:$first\n".toByteArray())
        }
        proxyPort = serve { s ->
            val inp = s.getInputStream()
            val head = readHeadForTest(inp)
            val auth = head.lines().firstOrNull { it.startsWith("Proxy-Authorization:") }
            val expected = "Basic " + Base64.getEncoder().encodeToString("u:p".toByteArray())
            if (auth?.substringAfter(": ") != expected) {
                s.getOutputStream().write("HTTP/1.1 407 Auth\r\n\r\n".toByteArray()); return@serve
            }
            seenConnects += head.lines().first()
            // Every CONNECT is sent to the echo server regardless of the requested target.
            val target = Socket("127.0.0.1", echoPort)
            s.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
            thread { runCatching { inp.copyTo(target.getOutputStream()) } }
            runCatching { target.getInputStream().copyTo(s.getOutputStream()) }
        }
    }

    @After fun tearDown() = servers.forEach { it.close() }

    private fun upstream(pass: String = "p") =
        Upstream(Endpoint("127.0.0.1", proxyPort, Protocol.HTTP, "u", pass), timeoutMs = 3000)

    @Test fun socksBridgeToHttpUpstream() {
        val bridge = LocalSocksServer(upstream()).start()
        try {
            val s = Socks5Client.connect(Endpoint("127.0.0.1", bridge.port, Protocol.SOCKS5, "", ""), "example.com", 443, 3000)
            s.getOutputStream().write("hello\n".toByteArray())
            assertEquals("echo:hello", BufferedReader(InputStreamReader(s.getInputStream())).readLine())
            assertEquals("CONNECT example.com:443 HTTP/1.1", seenConnects.single())
            s.close()
        } finally {
            bridge.stop()
        }
    }

    @Test fun relayHealthCountsWorkingAndFailingConnections() {
        val health = dev.resproxy.net.RelayHealth()
        val good = LocalSocksServer(upstream(), health).start()
        val bad = LocalSocksServer(upstream(pass = "nope"), health).start()
        try {
            Socks5Client.connect(Endpoint("127.0.0.1", good.port, Protocol.SOCKS5, "", ""), "example.com", 443, 3000).use { s ->
                s.getOutputStream().write("hi\n".toByteArray())
                assertEquals("echo:hi", BufferedReader(InputStreamReader(s.getInputStream())).readLine())
            }
            runCatching { Socks5Client.connect(Endpoint("127.0.0.1", bad.port, Protocol.SOCKS5, "", ""), "example.com", 443, 3000) }
            Thread.sleep(200)
            assertEquals(1L, health.ok.get())
            assertEquals(1L, health.failed.get())
        } finally {
            good.stop(); bad.stop()
        }
    }

    @Test fun silentConnectionCountsAsFailureWhileStillOpen() {
        val health = dev.resproxy.net.RelayHealth()
        val bridge = LocalSocksServer(upstream(), health, noResponseMs = 300).start()
        try {
            Socks5Client.connect(Endpoint("127.0.0.1", bridge.port, Protocol.SOCKS5, "", ""), "example.com", 443, 3000).use { s ->
                // No newline: the echo target never answers, and the app keeps the connection open.
                s.getOutputStream().write("hi".toByteArray())
                Thread.sleep(800)
                assertEquals(1L, health.failed.get())
                assertEquals(0L, health.ok.get())
            }
            Thread.sleep(200)
            assertEquals("counted once", 1L, health.failed.get())
            assertEquals(1L, health.accepted.get())
        } finally {
            bridge.stop()
        }
    }

    @Test fun staleFakeIpIsRefusedWithoutAskingTheProxy() {
        val health = dev.resproxy.net.RelayHealth()
        val bridge = LocalSocksServer(upstream(), health).start()
        try {
            val e = runCatching {
                Socks5Client.connect(Endpoint("127.0.0.1", bridge.port, Protocol.SOCKS5, "", ""), "100.64.0.7", 443, 3000)
            }.exceptionOrNull()
            assertEquals(4, (e as dev.resproxy.net.SocksRefusedException).code)
            assertEquals(1L, health.staleFakeIps.get())
            assertTrue(seenConnects.isEmpty())
            assertEquals(0L, health.failed.get())
        } finally {
            bridge.stop()
        }
    }

    @Test fun fakeDnsRange() {
        assertTrue(LocalSocksServer.isFakeDnsIp("100.64.0.0"))
        assertTrue(LocalSocksServer.isFakeDnsIp("100.127.255.255"))
        assertFalse(LocalSocksServer.isFakeDnsIp("100.128.0.1"))
        assertFalse(LocalSocksServer.isFakeDnsIp("100.63.1.1"))
        assertFalse(LocalSocksServer.isFakeDnsIp("100.64.0"))
        assertFalse(LocalSocksServer.isFakeDnsIp("100.64.example.com"))
        assertFalse(LocalSocksServer.isFakeDnsIp("2001:db8::1"))
    }

    @Test fun upstreamCanBeSwappedWithoutRestartingTheRelay() {
        val bridge = LocalSocksServer(null).also { it.start() }
        try {
            val ep = Endpoint("127.0.0.1", bridge.port, Protocol.SOCKS5, "", "")
            assertTrue(runCatching { Socks5Client.connect(ep, "example.com", 443, 3000) }.isFailure)
            bridge.upstream = upstream()
            Socks5Client.connect(ep, "example.com", 443, 3000).use { s ->
                s.getOutputStream().write("again\n".toByteArray())
                assertEquals("echo:again", BufferedReader(InputStreamReader(s.getInputStream())).readLine())
            }
        } finally {
            bridge.stop()
        }
    }

    @Test(expected = ProxyAuthException::class)
    fun wrongPasswordSurfacesAsAuthError() {
        upstream(pass = "nope").open("example.com", 443)
    }

    @Test fun httpProxyConnectAndAbsoluteUri() {
        val proxy = LocalHttpProxy(upstream()).start()
        try {
            Socket("127.0.0.1", proxy.port).use { s ->
                s.getOutputStream().write("CONNECT api.ipify.org:443 HTTP/1.1\r\nHost: api.ipify.org:443\r\n\r\n".toByteArray())
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                assertTrue(r.readLine().contains("200"))
                r.readLine()
                s.getOutputStream().write("ping\n".toByteArray())
                assertEquals("echo:ping", r.readLine())
            }
            Socket("127.0.0.1", proxy.port).use { s ->
                s.getOutputStream().write(
                    "GET http://example.com/a?b=1 HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray()
                )
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                assertEquals("echo:GET /a?b=1 HTTP/1.1", r.readLine())
            }
            assertTrue(seenConnects.contains("CONNECT example.com:80 HTTP/1.1"))
            assertTrue(proxy.bytesDown.get() > 0)
        } finally {
            proxy.stop()
        }
    }

    @Test fun authoritySplitting() {
        assertEquals("example.com" to 443, LocalHttpProxy.splitAuthority("example.com:443", 80))
        assertEquals("2001:db8::1" to 8443, LocalHttpProxy.splitAuthority("[2001:db8::1]:8443", 443))
        assertEquals("example.com" to 443, LocalHttpProxy.splitAuthority("example.com", 443))
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

    private fun readHeadForTest(inp: java.io.InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read(); if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
