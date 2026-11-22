package dev.resproxy.net

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
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
    /** Tailnet connections: not internet traffic, so a new or bad exit IP doesn't drop them ([closeConnections]). */
    private val kept = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
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
        // Everything, tailnet connections included (closeConnections keeps those).
        for (s in open) s.closeQuietly()
        pool.shutdownNow()
    }

    /** Drops every open connection (e.g. the exit IP turned out bad) but keeps accepting new ones. Tailnet ones stay. */
    fun closeConnections() {
        // The set changes while connections finish; its own iterator is safe for that (toList() isn't).
        for (s in open) if (s !in kept) s.closeQuietly()
    }

    /** Relays a tailnet connection: kept across exit IP changes, counted in [up] / [down] instead of the proxy totals. */
    protected fun relayTailnet(client: Socket, remote: Socket, clientIn: InputStream, up: AtomicLong, down: AtomicLong) {
        kept += client; kept += remote
        try {
            relay(client, remote, clientIn, upTotal = up, downTotal = down)
        } finally {
            kept -= client; kept -= remote
        }
    }

    protected abstract fun handle(client: Socket)

    /** Runs [task] on this server's connection pool. */
    protected fun background(task: () -> Unit) = pool.execute(task)

    /**
     * Bidirectional copy; returns (bytes up, bytes down) once both directions have finished. Once the client is done
     * sending, a remote that stays silent for [HALF_CLOSE_IDLE_MS] is closed: one that silently died (no FIN, no RST,
     * e.g. a dead exit node or VPN path) would otherwise hold this connection and its threads forever.
     */
    protected fun relay(
        client: Socket, remote: Socket, clientIn: InputStream = client.getInputStream(), upCount: AtomicLong = AtomicLong(),
        upTotal: AtomicLong = bytesUp, downTotal: AtomicLong = bytesDown, onFirstDown: () -> Unit = {},
    ): Pair<Long, Long> {
        open += remote
        val reaper = java.util.concurrent.atomic.AtomicReference<ScheduledFuture<*>?>()
        val down = AtomicLong()
        try {
            val up = pool.submit {
                copy(clientIn, remote.getOutputStream(), upTotal, upCount, null)
                runCatching { remote.shutdownOutput() }
                var seen = down.get()
                reaper.set(runCatching {
                    timers.scheduleWithFixedDelay({
                        val now = down.get()
                        if (now == seen) remote.closeQuietly() else seen = now
                    }, HALF_CLOSE_IDLE_MS, HALF_CLOSE_IDLE_MS, TimeUnit.MILLISECONDS)
                }.getOrNull())
            }
            copy(remote.getInputStream(), client.getOutputStream(), downTotal, down, onFirstDown)
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
    /** The tailnet route while the tailnet is on, else null. */
    private val tailnet: () -> TailnetRoute? = { null },
    /** Tailnet bytes (apps' upload / download), kept out of the proxy usage. */
    val tailnetUp: AtomicLong = AtomicLong(),
    val tailnetDown: AtomicLong = AtomicLong(),
    /**
     * Tailnet-only VPN (proxy off): UDP associations are answered here, DNS queries ([TailnetDns]) only, nothing goes to
     * a proxy. Only tailnet addresses are routed into that TUN, so the only UDP arriving is DNS to [Tailnet.DNS].
     */
    private val localDns: ((ByteArray) -> ByteArray?)? = null,
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
        // Tailnet first: not internet (no gate, no exit IP, not proxy health), and a bare MagicDNS name like `nas` would
        // otherwise count as a LAN name.
        val tn = tailnet()
        if (tn != null && tn.matches(host)) {
            val remote = try {
                tn.open(host, port)
            } catch (e: IOException) {
                out.write(reply((e as? SocksRefusedException)?.code ?: 4)); return
            }
            out.write(reply(0))
            relayTailnet(client, remote, inp, tailnetUp, tailnetDown)
            return
        }
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
            relay(client, remote, inp, upCount = upCount, onFirstDown = { gotData.set(true); if (counted.compareAndSet(false, true)) health.success() })
        } finally {
            watchdog?.cancel(false)
        }
        noResponse.run()
    }

    private fun udpAssociate(client: Socket, inp: DataInputStream, out: OutputStream) {
        localDns?.let { return dnsAssociate(inp, out, it) }
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

    /** A SOCKS5 UDP association answered locally: datagrams to port 53 get [dns]'s answer, others are dropped. */
    private fun dnsAssociate(inp: DataInputStream, out: OutputStream, dns: (ByteArray) -> ByteArray?) {
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (udp.localPort shr 8).toByte(), udp.localPort.toByte()))
            background {
                val buf = ByteArray(65_535)
                while (!udp.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    try { udp.receive(p) } catch (_: IOException) { break }
                    val (header, payload) = socksUdpSplit(buf, p.length) ?: continue
                    if (header.port != 53) continue
                    val from = p.socketAddress
                    background {
                        val answer = runCatching { dns(payload) }.getOrNull() ?: return@background
                        val reply = header.bytes + answer
                        runCatching { udp.send(DatagramPacket(reply, reply.size, from)) }
                    }
                }
            }
            // The association lives as long as its TCP connection.
            while (inp.read() >= 0) Unit
        } finally {
            udp.close()
        }
    }

    private fun reply(code: Int) = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    /** A SOCKS5 UDP header: its raw bytes (reused for the reply) and the destination port. */
    internal class UdpHeader(val bytes: ByteArray, val port: Int)

    companion object {
        /** Splits a SOCKS5 UDP datagram into header and payload; null for fragments and malformed ones. */
        internal fun socksUdpSplit(buf: ByteArray, len: Int): Pair<UdpHeader, ByteArray>? {
            if (len < 4 || buf[2].toInt() != 0) return null
            val addrEnd = when (buf[3].toInt()) {
                1 -> 4 + 4
                4 -> 4 + 16
                3 -> if (len > 4) 5 + (buf[4].toInt() and 0xff) else return null
                else -> return null
            }
            if (len < addrEnd + 2) return null
            val port = (buf[addrEnd].toInt() and 0xff shl 8) or (buf[addrEnd + 1].toInt() and 0xff)
            return UdpHeader(buf.copyOf(addrEnd + 2), port) to buf.copyOfRange(addrEnd + 2, len)
        }

        /** How long a new connection waits for a check in progress before it is refused (hev gives up after 10 s). */
        const val GATE_WAIT_MS = 8_000L
        /** An app that sent data and got nothing back for this long has a dead connection. */
        const val NO_RESPONSE_MS = 20_000L

        /** hev's fake-IP DNS range (HevConfig mapdns: 198.19.0.0/16). */
        fun isFakeDnsIp(host: String): Boolean {
            val p = host.split('.')
            if (p.size != 4 || p.any { it.toIntOrNull() !in 0..255 }) return false
            return p[0] == "198" && p[1] == "19"
        }
    }
}

/**
 * Local HTTP proxy (CONNECT + absolute-URI requests) that injects the provider credentials.
 * Used as the system HTTP proxy in proxy-only mode, and by the app itself for exit-IP and
 * health checks in every mode.
 */
class LocalHttpProxy(
    private val upstream: Upstream,
    private val gate: TrafficGate? = null,
    /** Proxy-only mode: tailnet targets skip the gate and the residential proxy. */
    private val tailnet: () -> TailnetRoute? = { null },
    private val tailnetUp: AtomicLong = AtomicLong(),
    private val tailnetDown: AtomicLong = AtomicLong(),
) : LoopbackServer("http-proxy") {

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
            val tn = tailnet()?.takeIf { it.matches(host) }
            val remote = (if (tn != null) openTailnet(out, tn, host, port) else openOr502(out, host, port)) ?: return
            out.write("$version 200 Connection established\r\n\r\n".toByteArray())
            out.flush()
            if (tn != null) relayTailnet(client, remote, inp, tailnetUp, tailnetDown) else relay(client, remote, inp)
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
        val tn = tailnet()?.takeIf { it.matches(uri.host) }
        val remote = (if (tn != null) openTailnet(out, tn, uri.host, port) else openOr502(out, uri.host, port)) ?: return
        remote.getOutputStream().apply { write(rewritten.toByteArray(Charsets.ISO_8859_1)); flush() }
        if (tn != null) relayTailnet(client, remote, inp, tailnetUp, tailnetDown) else relay(client, remote, inp)
    }

    private fun openTailnet(out: OutputStream, tn: TailnetRoute, host: String, port: Int): Socket? = try {
        tn.open(host, port)
    } catch (e: IOException) {
        respond(out, 502, "Bad Gateway", "Tailnet: ${e.message ?: "device not reachable"}"); null
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
