package dev.resproxy.net

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import dev.resproxy.data.Protocol

/** A tiny thread-per-connection TCP server bound to 127.0.0.1 on an ephemeral port. */
abstract class LoopbackServer(private val name: String) {

    val bytesDown = AtomicLong()
    val bytesUp = AtomicLong()

    private val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "$name-conn").apply { isDaemon = true } }
    private val open = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var running = false

    val port: Int get() = server.localPort

    /** Connections currently open (clients and their upstream sockets). */
    val openCount: Int get() = open.size

    fun start(): LoopbackServer {
        running = true
        Thread({
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    // A failed accept (a client that gave up while queued, out of file descriptors, ...) must not end
                    // the loop: apps' connections would then hang with nothing noticing.
                    if (!running || server.isClosed) break
                    Log.w(TAG, "$name accept: ${e.message}")
                    runCatching { Thread.sleep(50) }
                    continue
                }
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
        closeConnections()
        pool.shutdownNow()
    }

    /** Drops every open connection (e.g. the exit IP turned out bad) but keeps accepting new ones. */
    fun closeConnections() {
        // The set changes while connections finish; its own iterator is safe for that (toList() isn't).
        for (s in open) s.closeQuietly()
    }

    protected abstract fun handle(client: Socket)

    /**
     * Bidirectional copy; returns (bytes up, bytes down) once both directions have finished. Once the client is done
     * sending, a remote that stays silent for [HALF_CLOSE_IDLE_MS] is closed: one that silently died (no FIN, no RST,
     * e.g. a dead exit node or VPN path) would otherwise hold this connection and its threads forever.
     */
    protected fun relay(
        client: Socket, remote: Socket, clientIn: InputStream = client.getInputStream(), upCount: AtomicLong = AtomicLong(),
        onFirstDown: () -> Unit = {},
    ): Pair<Long, Long> {
        open += remote
        val reaper = java.util.concurrent.atomic.AtomicReference<ScheduledFuture<*>?>()
        val down = AtomicLong()
        try {
            val up = pool.submit {
                copy(clientIn, remote.getOutputStream(), bytesUp, upCount, null)
                runCatching { remote.shutdownOutput() }
                var seen = down.get()
                reaper.set(runCatching {
                    timers.scheduleWithFixedDelay({
                        val now = down.get()
                        if (now == seen) remote.closeQuietly() else seen = now
                    }, HALF_CLOSE_IDLE_MS, HALF_CLOSE_IDLE_MS, TimeUnit.MILLISECONDS)
                }.getOrNull())
            }
            copy(remote.getInputStream(), client.getOutputStream(), bytesDown, down, onFirstDown)
            runCatching { client.shutdownOutput() }
            runCatching { up.get() }
            return upCount.get() to down.get()
        } finally {
            reaper.get()?.cancel(false)
            remote.closeQuietly(); open -= remote
        }
    }

    private fun copy(inp: InputStream, out: OutputStream, counter: AtomicLong, local: AtomicLong, onFirst: (() -> Unit)?) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                counter.addAndGet(n.toLong())
                if (local.getAndAdd(n.toLong()) == 0L) onFirst?.invoke()
            }
            out.flush()
        } catch (_: IOException) {
        }
    }

    protected companion object {
        const val TAG = "ResProxy"
        const val HALF_CLOSE_IDLE_MS = 120_000L
        /** One timer thread for all relay watchdogs. */
        val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "relay-timer").apply { isDaemon = true }
        }
    }
}

/**
 * What the TUN relay has seen recently: lets the health loop tell "the proxy answers probes" apart
 * from "apps' connections actually work".
 */
class RelayHealth {
    /** Connections the tunnel engine opened to the relay, whatever became of them. */
    val accepted = AtomicLong()
    /** Connections whose upstream opened and that received data back. */
    val ok = AtomicLong()
    /** Upstream failed, or the app sent data and got nothing back (within [LocalSocksServer.NO_RESPONSE_MS]). */
    val failed = AtomicLong()
    /** The proxy answered but couldn't reach the target (SOCKS reply 1-8): the target's problem, not ours. */
    val refused = AtomicLong()
    /**
     * Targets in the fake-IP range that the tunnel engine couldn't map back to a name: an app kept an address from
     * before the engine restarted. Refused right away, so the app looks the name up again.
     */
    val staleFakeIps = AtomicLong()
    @Volatile var lastOkAt = 0L
    @Volatile var lastFailAt = 0L
    @Volatile var lastError: String? = null

    private val failedHosts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun success() { ok.incrementAndGet(); lastOkAt = System.currentTimeMillis() }
    fun failure(host: String, why: String?) {
        val now = System.currentTimeMillis()
        failed.incrementAndGet(); lastFailAt = now; lastError = "$host: $why"
        failedHosts[host] = now
        if (failedHosts.size > 64) failedHosts.entries.removeIf { now - it.value > 120_000 }
    }

    /** Different targets that failed since [since]: one dead website must not look like a dead connection. */
    fun distinctFailedHosts(since: Long) = failedHosts.count { it.value >= since }

    fun summary() = "accepted ${accepted.get()}, ok ${ok.get()}, failed ${failed.get()}, refused ${refused.get()}, " +
        "stale fake IPs ${staleFakeIps.get()}" + (lastError?.let { "; last failure $it" } ?: "")
}

/**
 * Local unauthenticated SOCKS5 that hev-socks5-tunnel talks to. Every app connection passes through
 * here, so upstream addresses are resolved fresh (with DoH fallback) and [health] reflects real traffic.
 * UDP ASSOCIATE is passed through to a SOCKS5 upstream when it is reached directly.
 */
class LocalSocksServer(
    /** Swappable: repairs keep this server (and its port), so the tunnel engine keeps running. Null: refuse. */
    @Volatile var upstream: Upstream?,
    val health: RelayHealth = RelayHealth(),
    /** Internet traffic waits here until the exit IP is known to be clean; null = always open. */
    private val gate: TrafficGate? = null,
    /** LAN bypass: connect local-network targets directly (needed when Android routes the LAN into the VPN). */
    private val lanDirect: () -> Boolean = { false },
    private val lanResolve: (String) -> InetAddress? = { LanTargets.resolve(it) },
    private val gateWaitMs: Long = GATE_WAIT_MS,
    private val noResponseMs: Long = NO_RESPONSE_MS,
) : LoopbackServer("socks-relay") {

    override fun handle(client: Socket) {
        health.accepted.incrementAndGet()
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
        when (cmd) {
            1 -> connect(client, inp, out, host, port)
            3 -> udpAssociate(client, inp, out)
            else -> out.write(reply(7))
        }
    }

    private fun connect(client: Socket, inp: DataInputStream, out: OutputStream, host: String, port: Int) {
        if (isFakeDnsIp(host)) {
            // Only the tunnel engine hands these out; one it can't map back to a name is useless to any proxy.
            health.staleFakeIps.incrementAndGet()
            out.write(reply(4)); return // host unreachable
        }
        val lan = if (lanDirect()) lanResolve(host) else null
        if (lan != null) {
            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(lan, port), 10_000)
                remote.tcpNoDelay = true
            } catch (e: IOException) {
                remote.closeQuietly()
                out.write(reply(5)); return
            }
            out.write(reply(0))
            relay(client, remote, inp)
            return
        }
        if (gate != null && !gate.await(gateWaitMs)) { out.write(reply(2)); return } // 2 = not allowed by ruleset
        val up = upstream ?: run { out.write(reply(1)); return }
        val remote = try {
            up.open(host, port)
        } catch (e: IOException) {
            if (e is SocksRefusedException) health.refused.incrementAndGet() // target refused ≠ broken proxy
            else health.failure(host, e.message)
            out.write(reply(if (e is SocksRefusedException) e.code else 5)); return
        }
        out.write(reply(0))
        // Counted once: as soon as the app has waited noResponseMs for an answer, or when it gives up, whichever comes
        // first. Apps keep dead connections open for minutes; waiting for them to close hid a dead connection.
        val gotData = AtomicBoolean(false)
        val counted = AtomicBoolean(false)
        val upCount = AtomicLong()
        val noResponse = Runnable {
            if (!gotData.get() && upCount.get() > 0 && counted.compareAndSet(false, true)) health.failure(host, "no response")
        }
        val watchdog = runCatching { timers.schedule(noResponse, noResponseMs, TimeUnit.MILLISECONDS) }.getOrNull()
        try {
            relay(client, remote, inp, upCount = upCount) { gotData.set(true); if (counted.compareAndSet(false, true)) health.success() }
        } finally {
            watchdog?.cancel(false)
        }
        noResponse.run()
    }

    private fun udpAssociate(client: Socket, inp: DataInputStream, out: OutputStream) {
        val ep = upstream?.endpoint ?: run { out.write(reply(1)); return }
        if (gate != null && !gate.isOpen) { out.write(reply(2)); return }
        if (ep.protocol != Protocol.SOCKS5 || ep.host == "127.0.0.1") { out.write(reply(7)); return }
        val (ctrl, relayAddr) = try {
            Socks5Client.udpAssociate(ep, 10_000)
        } catch (e: IOException) {
            out.write(reply(7)); return
        }
        val a = relayAddr.address.address
        out.write(byteArrayOf(5, 0, 0, if (a.size == 4) 1 else 4) + a + byteArrayOf((relayAddr.port shr 8).toByte(), relayAddr.port.toByte()))
        relay(client, ctrl, inp) // keeps the association alive until either side closes
    }

    private fun reply(code: Int) = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    companion object {
        /** How long a new connection waits for a check in progress before it is refused (hev gives up after 10 s). */
        const val GATE_WAIT_MS = 8_000L
        /** An app that sent data and got nothing back for this long has a dead connection. */
        const val NO_RESPONSE_MS = 20_000L

        /** hev's fake-IP DNS range (HevConfig mapdns: 100.64.0.0/10). */
        fun isFakeDnsIp(host: String): Boolean {
            val p = host.split('.')
            if (p.size != 4 || p.any { it.toIntOrNull() !in 0..255 }) return false
            return p[0] == "100" && p[1].toInt() in 64..127
        }
    }
}

/**
 * Local HTTP proxy (CONNECT + absolute-URI requests) that injects the provider credentials.
 * Used as the system HTTP proxy in proxy-only mode, and by the app itself for exit-IP and
 * health checks in every mode.
 */
class LocalHttpProxy(private val upstream: Upstream, private val gate: TrafficGate? = null) : LoopbackServer("http-proxy") {

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
        if (gate != null && !gate.await(LocalSocksServer.GATE_WAIT_MS)) {
            respond(out, 503, "Service Unavailable", "Traffic is blocked until ResProxy finds a clean IP")
            null
        } else upstream.open(host, port)
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
