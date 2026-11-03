package dev.resproxy.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * System DNS with a DNS-over-HTTPS fallback. Some networks (router filters, ISP blocks) refuse or
 * sinkhole proxy-provider and IP-reputation domains; DoH is reached by IP so it needs no DNS itself.
 */
object Resolver : Dns {

    private val doh = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (isIpLiteral(hostname)) listOf(InetAddress.getByName(hostname)) else throw UnknownHostException(hostname)
        })
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val endpoints = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val TTL_MS = 10 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    override fun lookup(hostname: String): List<InetAddress> = resolveAll(hostname)

    fun resolve(host: String): InetAddress = resolveAll(host).first()

    fun resolveAll(host: String): List<InetAddress> {
        if (isIpLiteral(host)) return listOf(InetAddress.getByName(host.trim('[', ']')))
        cache[host]?.let { (ts, addrs) -> if (System.currentTimeMillis() - ts < TTL_MS) return addrs }
        val system = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
        if (system.isNotEmpty()) return system
        val viaDoh = dohLookup(host)
        if (viaDoh.isEmpty()) throw UnknownHostException("Can't resolve $host (blocked by network DNS?)")
        cache[host] = System.currentTimeMillis() to viaDoh
        return viaDoh
    }

    private fun dohLookup(host: String): List<InetAddress> {
        for (base in endpoints) {
            val addrs = runCatching {
                val req = Request.Builder().url("$base?name=$host&type=A").header("Accept", "application/dns-json").build()
                doh.newCall(req).execute().use { r -> if (r.isSuccessful) parse(r.body?.string().orEmpty()) else emptyList() }
            }.getOrDefault(emptyList())
            if (addrs.isNotEmpty()) return addrs
        }
        return emptyList()
    }

    /** JSON DoH answer (RFC 8427-style, as served by Cloudflare and Google); A records only. */
    internal fun parse(body: String): List<InetAddress> {
        val answers = json.parseToJsonElement(body).jsonObject["Answer"] as? JsonArray ?: return emptyList()
        return answers.mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.intOrNull == 1 }
            .mapNotNull { (it["data"] as? JsonPrimitive)?.contentOrNull }
            .filter { isIpv4Literal(it) && it != "0.0.0.0" }
            .map { InetAddress.getByName(it) }
    }

    internal fun isIpLiteral(host: String) = isIpv4Literal(host) || host.contains(':')
}
