package dev.resproxy.reputation

import dev.resproxy.data.ApiKeys
import dev.resproxy.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ExitIp(val ip: String, val latencyMs: Long)

class ReputationRepository(private val settings: SettingsRepository) {

    /** Reputation APIs are called directly (the app is excluded from its own VPN) to save proxy GB. */
    private val direct = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, ReputationReport>()

    /** Client that goes through the local credential-injecting proxy, i.e. through the residential IP. */
    fun proxiedClient(localProxyPort: Int): OkHttpClient = direct.newBuilder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localProxyPort)))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun exitIp(localProxyPort: Int): ExitIp = withContext(Dispatchers.IO) {
        val client = proxiedClient(localProxyPort)
        var last: Exception? = null
        for (url in IP_ECHO) {
            try {
                val start = System.nanoTime()
                val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("${r.code} ${r.message}")
                    r.body?.string().orEmpty()
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                val ip = if (body.trimStart().startsWith("{")) {
                    Json.parseToJsonElement(body).jsonObject["ip"]!!.jsonPrimitive.content
                } else body.trim()
                if (ip.isNotEmpty()) return@withContext ExitIp(ip, ms)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Could not determine exit IP: ${last?.message}", last)
    }

    /** Health probe through the proxy. Returns latency in ms, or null on failure. */
    suspend fun probe(localProxyPort: Int): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.nanoTime()
            proxiedClient(localProxyPort).newCall(Request.Builder().url(PROBE_URL).build()).execute().use {
                if (it.code != 204 && !it.isSuccessful) return@runCatching null
            }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
    }

    fun cached(ip: String): ReputationReport? = cache[ip]?.takeIf { System.currentTimeMillis() - it.checkedAt < CACHE_MS }

    suspend fun check(ip: String, force: Boolean = false, fallbackProxyPort: Int? = null): ReputationReport {
        if (!force) cached(ip)?.let { return it }
        val keys = settings.currentKeys()
        val results = coroutineScope {
            sources(ip, keys, fallbackProxyPort?.let(::proxiedClient)).map { (id, call) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(25_000) { runCatching { call() }.getOrElse { SourceResult.failed(id, it.message ?: it.javaClass.simpleName) } }
                        ?: SourceResult.failed(id, "timeout")
                }
            }.awaitAll()
        }
        return ReputationAggregator.aggregate(ip, results).also { cache[ip] = it }
    }

    private fun sources(ip: String, k: ApiKeys, fallback: OkHttpClient?): List<Pair<SourceId, () -> SourceResult>> = buildList {
        fun get(url: String, vararg h: Pair<String, String>) = fetch(fallback, url, *h)
        add(SourceId.IPAPI_IS to {
            val url = "https://api.ipapi.is/".toHttpUrl().newBuilder().addQueryParameter("q", ip)
                .apply { if (k.ipapiIs.isNotBlank()) addQueryParameter("key", k.ipapiIs.trim()) }.build()
            Parsers.ipapiIs(get(url.toString()))
        })
        add(SourceId.PROXYCHECK to {
            val url = "https://proxycheck.io/v2/$ip".toHttpUrl().newBuilder()
                .addQueryParameter("vpn", "1").addQueryParameter("asn", "1").addQueryParameter("risk", "1")
                .apply { if (k.proxycheck.isNotBlank()) addQueryParameter("key", k.proxycheck.trim()) }.build()
            Parsers.proxycheck(get(url.toString()), ip)
        })
        add(SourceId.IP_API to {
            Parsers.ipApi(get("http://ip-api.com/json/$ip?fields=status,message,country,countryCode,regionName,city,isp,org,as,mobile,proxy,hosting,query"))
        })
        if (k.abuseIpDb.isNotBlank()) add(SourceId.ABUSEIPDB to {
            Parsers.abuseIpDb(
                get("https://api.abuseipdb.com/api/v2/check?ipAddress=$ip&maxAgeInDays=90", "Key" to k.abuseIpDb.trim())
            )
        })
        if (k.ipqs.isNotBlank()) add(SourceId.IPQS to {
            Parsers.ipqs(get("https://ipqualityscore.com/api/json/ip/${k.ipqs.trim()}/$ip?strictness=1&allow_public_access_points=true"))
        })
        if (k.scamalyticsUser.isNotBlank() && k.scamalyticsKey.isNotBlank()) add(SourceId.SCAMALYTICS to {
            val url = "https://api11.scamalytics.com/v3/${k.scamalyticsUser.trim()}/".toHttpUrl().newBuilder()
                .addQueryParameter("key", k.scamalyticsKey.trim()).addQueryParameter("ip", ip).build()
            Parsers.scamalytics(get(url.toString()))
        })
        Dnsbl.zones.forEach { (id, zone) -> add(id to { Dnsbl.lookup(id, zone, ip) }) }
    }

    /** Direct first; if that fails (e.g. a filtering DNS sinkholes the API host), retry through the proxy. */
    private fun fetch(fallback: OkHttpClient?, url: String, vararg headers: Pair<String, String>): String {
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return try {
            execute(direct, req)
        } catch (e: IOException) {
            if (fallback == null) throw e
            execute(fallback, req)
        }
    }

    private fun execute(client: OkHttpClient, req: Request): String =
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            // Error bodies are parsed only when they're JSON (API error objects), not proxy/HTML errors.
            if (!r.isSuccessful && !body.trimStart().startsWith("{")) throw IOException("HTTP ${r.code}")
            body
        }

    private companion object {
        val IP_ECHO = listOf("https://api.ipify.org?format=json", "https://ifconfig.me/ip", "https://icanhazip.com")
        const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
        const val CACHE_MS = 60 * 60 * 1000L
    }
}
