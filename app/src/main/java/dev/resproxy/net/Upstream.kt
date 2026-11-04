package dev.resproxy.net

import dev.resproxy.data.Protocol
import dev.resproxy.provider.Endpoint
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

class ProxyAuthException(message: String) : IOException(message)

/** The proxy works but the target refused / was unreachable (SOCKS5 reply [code]). */
class SocksRefusedException(message: String, val code: Int) : IOException(message)

/**
 * Opens TCP tunnels to arbitrary targets through the configured residential proxy.
 * [onRtt] gets round-trip samples (ms) to the proxy itself, measured during the handshake.
 */
class Upstream(val endpoint: Endpoint, private val timeoutMs: Int = 15_000, private val onRtt: ((Long) -> Unit)? = null) {

    fun open(host: String, port: Int): Socket = when (endpoint.protocol) {
        Protocol.SOCKS5 -> Socks5Client.connect(endpoint, host, port, timeoutMs, onRtt)
        Protocol.HTTP -> HttpConnectClient.connect(endpoint, host, port, timeoutMs)
    }
}

object Socks5Client {

    fun connect(ep: Endpoint, host: String, port: Int, timeoutMs: Int, onRtt: ((Long) -> Unit)? = null): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(Resolver.resolve(ep.host), ep.port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            handshake(s, ep.username, ep.password, host, port, onRtt = onRtt)
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            s.closeQuietly()
            throw e
        }
    }

    /** UDP ASSOCIATE; returns the control connection (keep it open) and the proxy's UDP relay address. */
    fun udpAssociate(ep: Endpoint, timeoutMs: Int): Pair<Socket, InetSocketAddress> {
        val s = Socket()
        try {
            val proxyIp = Resolver.resolve(ep.host)
            s.connect(InetSocketAddress(proxyIp, ep.port), timeoutMs)
            s.soTimeout = timeoutMs
            val bound = handshake(s, ep.username, ep.password, "0.0.0.0", 0, cmd = 3)
            s.soTimeout = 0
            val addr = if (bound.address.isAnyLocalAddress) InetSocketAddress(proxyIp, bound.port) else bound
            return s to addr
        } catch (e: Exception) {
            s.closeQuietly()
            throw e
        }
    }

    /** Returns the BND address of the reply. */
    internal fun handshake(
        s: Socket, user: String, pass: String, host: String, port: Int, cmd: Int = 1, onRtt: ((Long) -> Unit)? = null,
    ): InetSocketAddress {
        val out = s.getOutputStream()
        val inp = DataInputStream(s.getInputStream())
        val auth = user.isNotEmpty()
        out.write(if (auth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        if (inp.readUnsignedByte() != 5) throw IOException("Not a SOCKS5 proxy")
        when (inp.readUnsignedByte()) {
            0 -> Unit
            2 -> {
                val u = user.toByteArray(); val p = pass.toByteArray()
                if (u.size > 255 || p.size > 255) throw ProxyAuthException("Username/password too long for SOCKS5")
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                val t0 = System.nanoTime()
                inp.readUnsignedByte()
                onRtt?.invoke((System.nanoTime() - t0) / 1_000_000)
                if (inp.readUnsignedByte() != 0) throw ProxyAuthException("Proxy rejected username/password")
            }
            0xFF -> throw ProxyAuthException("Proxy requires authentication")
            else -> throw IOException("Unsupported SOCKS5 auth method")
        }
        out.write(byteArrayOf(5, cmd.toByte(), 0) + encodeAddress(host) + byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
        if (inp.readUnsignedByte() != 5) throw IOException("Bad SOCKS5 reply")
        val rep = inp.readUnsignedByte()
        inp.readUnsignedByte()
        val addr: InetAddress = when (inp.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { inp.readFully(it) })
            4 -> InetAddress.getByAddress(ByteArray(16).also { inp.readFully(it) })
            3 -> { inp.skipFully(inp.readUnsignedByte()); InetAddress.getByAddress(ByteArray(4)) }
            else -> throw IOException("Bad SOCKS5 address type")
        }
        val bport = inp.readUnsignedShort()
        if (rep in 1..8) throw SocksRefusedException("SOCKS5 $host:$port: ${socksError(rep)}", rep)
        if (rep != 0) throw IOException("SOCKS5 connect to $host:$port failed (${socksError(rep)})")
        return InetSocketAddress(addr, bport)
    }

    private fun encodeAddress(host: String): ByteArray = when {
        isIpv4Literal(host) -> byteArrayOf(1) + InetAddress.getByName(host).address
        host.contains(':') -> byteArrayOf(4) + InetAddress.getByName(host.trim('[', ']')).address
        else -> host.toByteArray().let { byteArrayOf(3, it.size.toByte()) + it }
    }

    private fun socksError(code: Int) = when (code) {
        1 -> "general failure"; 2 -> "not allowed by ruleset"; 3 -> "network unreachable"
        4 -> "host unreachable"; 5 -> "connection refused"; 6 -> "TTL expired"
        7 -> "command not supported"; 8 -> "address type not supported"; else -> "code $code"
    }
}

object HttpConnectClient {

    fun connect(ep: Endpoint, host: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(Resolver.resolve(ep.host), ep.port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
            val req = buildString {
                append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                append("Host: ").append(authority).append("\r\n")
                if (ep.username.isNotEmpty()) {
                    val token = Base64.getEncoder().encodeToString("${ep.username}:${ep.password}".toByteArray())
                    append("Proxy-Authorization: Basic ").append(token).append("\r\n")
                }
                append("Proxy-Connection: keep-alive\r\n\r\n")
            }
            s.getOutputStream().apply { write(req.toByteArray()); flush() }
            val head = readHead(s.getInputStream()) ?: throw IOException("Proxy closed connection")
            val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
            when (status) {
                200 -> Unit
                407 -> throw ProxyAuthException("Proxy rejected username/password (407)")
                else -> throw IOException("HTTP CONNECT to $host:$port failed: ${head.lineSequence().first()}")
            }
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            s.closeQuietly()
            throw e
        }
    }
}

/** Reads an HTTP header block byte-by-byte (so nothing past the blank line is consumed). */
internal fun readHead(inp: InputStream, max: Int = 64 * 1024): String? {
    val buf = ByteArrayOutputStream()
    var state = 0
    while (true) {
        val b = inp.read()
        if (b < 0) return if (buf.size() == 0) null else throw IOException("Truncated HTTP header")
        buf.write(b)
        if (buf.size() > max) throw IOException("HTTP header too large")
        state = when {
            b == '\r'.code && (state == 0 || state == 2) -> state + 1
            b == '\n'.code && (state == 1 || state == 3) -> state + 1
            else -> 0
        }
        if (state == 4) return buf.toString(Charsets.ISO_8859_1.name())
    }
}

internal fun isIpv4Literal(host: String) = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)

internal fun DataInputStream.skipFully(n: Int) { readFully(ByteArray(n)) }

internal fun Socket.closeQuietly() = runCatching { close() }
