package dev.resproxy

import dev.resproxy.data.Protocol
import dev.resproxy.net.ProxyAuthException
import dev.resproxy.net.SocksRefusedException
import dev.resproxy.net.Upstream
import dev.resproxy.net.exitNodeDead
import dev.resproxy.provider.Endpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.UnknownHostException
import kotlin.concurrent.thread

/** A sticky session whose residential exit node went offline: the proxy logs us in but can't reach anything. */
class ExitNodeTest {

    private val server = ServerSocket(0)

    @After fun tearDown() = server.close()

    /** SOCKS5 proxy that accepts u/p and answers every CONNECT with "host unreachable" (rep 4), like Geonode does. */
    private fun deadExitSocks() = thread(isDaemon = true) {
        runCatching {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
                inp.readUnsignedByte(); repeat(inp.readUnsignedByte()) { inp.readUnsignedByte() }
                out.write(byteArrayOf(5, 2)); out.flush()
                inp.readUnsignedByte()
                inp.readNBytes(inp.readUnsignedByte()); inp.readNBytes(inp.readUnsignedByte())
                out.write(byteArrayOf(1, 0)); out.flush()
                inp.readNBytes(3)
                when (inp.readUnsignedByte()) {
                    1 -> inp.readNBytes(4)
                    3 -> inp.readNBytes(inp.readUnsignedByte())
                    4 -> inp.readNBytes(16)
                }
                inp.readNBytes(2)
                out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            }
        }
    }

    @Test fun deadExitNodeIsRecognised() {
        deadExitSocks()
        val up = Upstream(Endpoint("127.0.0.1", server.localPort, Protocol.SOCKS5, "u", "p"), timeoutMs = 3000)
        val e = runCatching { up.open("connectivitycheck.gstatic.com", 80) }.exceptionOrNull()
        assertTrue("got $e", e is SocksRefusedException)
        assertEquals(4, (e as SocksRefusedException).code)
        assertTrue(exitNodeDead(e))
    }

    @Test fun onlyTargetFailuresCountAsDeadExit() {
        assertTrue(exitNodeDead(IOException("HTTP CONNECT to connectivitycheck.gstatic.com:80 failed: HTTP/1.1 502 Bad Gateway")))
        assertTrue(exitNodeDead(IOException("HTTP CONNECT to x:80 failed: HTTP/1.1 504 Gateway Timeout")))
        // The proxy itself is unreachable or rejects us: a new session wouldn't help.
        assertFalse(exitNodeDead(ProxyAuthException("Proxy rejected username/password (407)")))
        assertFalse(exitNodeDead(IOException("HTTP CONNECT to x:80 failed: HTTP/1.1 403 Forbidden")))
        assertFalse(exitNodeDead(UnknownHostException("gate.geonode.com")))
        assertFalse(exitNodeDead(IOException("Proxy closed connection")))
    }
}
