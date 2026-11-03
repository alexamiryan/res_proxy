package dev.resproxy.net

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A tiny thread-per-connection TCP server bound to 127.0.0.1 on an ephemeral port. */
abstract class LoopbackServer(private val name: String) {

    val bytesDown = AtomicLong()
    val bytesUp = AtomicLong()

    private val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "$name-conn").apply { isDaemon = true } }
    private val open = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var running = false

    val port: Int get() = server.localPort

    fun start(): LoopbackServer {
        running = true
        Thread({
            while (running) {
                val client = try { server.accept() } catch (e: IOException) { break }
                open += client
                pool.execute {
                    try {
                        client.tcpNoDelay = true
                        handle(client)
                    } catch (e: Exception) {
                        if (running) Log.d(TAG, "$name: ${e.message}")
                    } finally {
                        client.closeQuietly(); open -= client
                    }
                }
            }
        }, "$name-accept").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        open.toList().forEach { it.closeQuietly() }
        pool.shutdownNow()
    }

    protected abstract fun handle(client: Socket)

    /** Bidirectional copy; returns once both directions have finished. */
    protected fun relay(client: Socket, remote: Socket, clientIn: InputStream = client.getInputStream()) {
        open += remote
        try {
            val up = pool.submit { copy(clientIn, remote.getOutputStream(), bytesUp); runCatching { remote.shutdownOutput() } }
            copy(remote.getInputStream(), client.getOutputStream(), bytesDown)
            runCatching { client.shutdownOutput() }
            runCatching { up.get() }
        } finally {
            remote.closeQuietly(); open -= remote
        }
    }

    private fun copy(inp: InputStream, out: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                counter.addAndGet(n.toLong())
            }
            out.flush()
        } catch (_: IOException) {
        }
    }

    protected companion object {
        const val TAG = "ResProxy"
    }
}

/**
 * Unauthenticated local SOCKS5 (CONNECT only) that forwards through [upstream].
 * Lets hev-socks5-tunnel use HTTP-only upstream proxies.
 */
class LocalSocksServer(private val upstream: Upstream) : LoopbackServer("socks-bridge") {

    override fun handle(client: Socket) {
        val inp = DataInputStream(client.getInputStream())
        val out = client.getOutputStream()
        if (inp.readUnsignedByte() != 5) return
        inp.skipFully(inp.readUnsignedByte())
        out.write(byteArrayOf(5, 0))
        if (inp.readUnsignedByte() != 5) return
        val cmd = inp.readUnsignedByte()
        inp.readUnsignedByte()
        val host = when (inp.readUnsignedByte()) {
            1 -> ByteArray(4).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            4 -> ByteArray(16).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            3 -> ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }.let { String(it) }
            else -> return
        }
        val port = inp.readUnsignedShort()
        if (cmd != 1) {
            out.write(reply(7)); return
        }
        val remote = try {
            upstream.open(host, port)
        } catch (e: IOException) {
            out.write(reply(5)); return
        }
        out.write(reply(0))
        relay(client, remote, inp)
    }

    private fun reply(code: Int) = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)
}

/**
 * Local HTTP proxy (CONNECT + absolute-URI requests) that injects the provider credentials.
 * Used as the system HTTP proxy in proxy-only mode, and by the app itself for exit-IP and
 * health checks in every mode.
 */
class LocalHttpProxy(private val upstream: Upstream) : LoopbackServer("http-proxy") {

    override fun handle(client: Socket) {
        val inp = client.getInputStream()
        val out = client.getOutputStream()
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.first().split(' ')
        if (parts.size < 3) return respond(out, 400, "Bad Request")
        val (method, target, version) = parts

        if (method.equals("CONNECT", ignoreCase = true)) {
            val (host, port) = splitAuthority(target, 443) ?: return respond(out, 400, "Bad Request")
            val remote = openOr502(out, host, port) ?: return
            out.write("$version 200 Connection established\r\n\r\n".toByteArray())
            out.flush()
            relay(client, remote, inp)
            return
        }

        val uri = runCatching { URI(target) }.getOrNull()
        if (uri?.host == null || uri.scheme?.lowercase() != "http") return respond(out, 400, "Bad Request")
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hop = setOf("proxy-connection", "proxy-authorization", "connection", "keep-alive")
        val rewritten = buildString {
            append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
            lines.drop(1).filter { it.substringBefore(':').trim().lowercase() !in hop }
                .forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val remote = openOr502(out, uri.host, port) ?: return
        remote.getOutputStream().apply { write(rewritten.toByteArray(Charsets.ISO_8859_1)); flush() }
        relay(client, remote, inp)
    }

    private fun openOr502(out: OutputStream, host: String, port: Int): Socket? = try {
        upstream.open(host, port)
    } catch (e: IOException) {
        respond(out, 502, "Bad Gateway", e.message ?: "upstream error"); null
    }

    private fun respond(out: OutputStream, code: Int, reason: String, body: String = reason) {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: text/plain\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
        out.flush()
    }

    internal companion object {
        fun splitAuthority(authority: String, defaultPort: Int): Pair<String, Int>? {
            if (authority.startsWith("[")) {
                val end = authority.indexOf(']').takeIf { it > 0 } ?: return null
                val port = authority.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return authority.substring(1, end) to port
            }
            val i = authority.lastIndexOf(':')
            if (i < 0) return authority to defaultPort
            val port = authority.substring(i + 1).toIntOrNull() ?: return null
            return authority.substring(0, i) to port
        }
    }
}
