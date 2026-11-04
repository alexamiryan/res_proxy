package dev.resproxy.net

import java.io.IOException

/** Cheap end-to-end check of the upstream proxy (and its credentials). */
object ProxyProbe {
    private const val HOST = "connectivitycheck.gstatic.com"

    /** Returns round-trip latency in ms; throws (e.g. [ProxyAuthException]) when the proxy is unusable. */
    fun run(upstream: Upstream): Long {
        val start = System.nanoTime()
        upstream.open(HOST, 80).use { s ->
            s.soTimeout = 15_000
            s.getOutputStream().apply {
                write("GET /generate_204 HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val status = readHead(s.getInputStream())?.lineSequence()?.first() ?: throw IOException("Proxy returned no response")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            if (code != 204 && code != 200) throw IOException("Unexpected response through proxy: $status")
        }
        return (System.nanoTime() - start) / 1_000_000
    }
}
