package dev.resproxy.reputation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Pure JSON → [SourceResult] parsers, kept separate from the HTTP calls so they can be unit-tested. */
object Parsers {

    fun ipapiIs(body: String): SourceResult {
        val o = obj(body)
        o.str("error")?.let { return SourceResult.failed(SourceId.IPAPI_IS, it) }
        val company = o.obj("company")
        val asn = o.obj("asn")
        val loc = o.obj("location")
        return SourceResult(
            SourceId.IPAPI_IS,
            proxy = o.bool("is_proxy"),
            vpn = o.bool("is_vpn"),
            tor = o.bool("is_tor"),
            datacenter = o.bool("is_datacenter"),
            abuser = o.bool("is_abuser"),
            country = loc?.str("country"),
            countryCode = loc?.str("country_code"),
            region = loc?.str("state"),
            city = loc?.str("city"),
            isp = company?.str("name") ?: asn?.str("org"),
            asn = asn?.get("asn")?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { "AS$it" },
            connectionType = company?.str("type"),
            note = company?.str("abuser_score")?.let { "Abuser score $it" },
        )
    }

    fun proxycheck(body: String, ip: String): SourceResult {
        val o = obj(body)
        val status = o.str("status")
        if (status != null && status != "ok" && status != "warning") {
            return SourceResult.failed(SourceId.PROXYCHECK, o.str("message") ?: status)
        }
        val r = o.obj(ip) ?: return SourceResult.failed(SourceId.PROXYCHECK, o.str("message") ?: "no data")
        val type = r.str("type")
        return SourceResult(
            SourceId.PROXYCHECK,
            proxy = r.str("proxy")?.equals("yes", true),
            vpn = type?.equals("VPN", true),
            tor = type?.equals("TOR", true),
            datacenter = type?.equals("Hosting", true),
            score = r.int("risk"),
            country = r.str("country"),
            countryCode = r.str("isocode"),
            region = r.str("region"),
            city = r.str("city"),
            isp = r.str("provider") ?: r.str("organisation"),
            asn = r.str("asn"),
            connectionType = type,
        )
    }

    fun ipApi(body: String): SourceResult {
        val o = obj(body)
        if (o.str("status") != "success") return SourceResult.failed(SourceId.IP_API, o.str("message") ?: "failed")
        return SourceResult(
            SourceId.IP_API,
            proxy = o.bool("proxy"),
            datacenter = o.bool("hosting"),
            country = o.str("country"),
            countryCode = o.str("countryCode"),
            region = o.str("regionName"),
            city = o.str("city"),
            isp = o.str("isp") ?: o.str("org"),
            asn = o.str("as")?.substringBefore(' '),
            connectionType = if (o.bool("mobile") == true) "mobile" else null,
        )
    }

    fun abuseIpDb(body: String): SourceResult {
        val o = obj(body)
        o["errors"]?.let { return SourceResult.failed(SourceId.ABUSEIPDB, it.toString().take(120)) }
        val d = o.obj("data") ?: return SourceResult.failed(SourceId.ABUSEIPDB, "no data")
        val usage = d.str("usageType")
        return SourceResult(
            SourceId.ABUSEIPDB,
            tor = d.bool("isTor"),
            datacenter = usage?.contains("Data Center", true),
            score = d.int("abuseConfidenceScore"),
            reports = d.int("totalReports"),
            countryCode = d.str("countryCode"),
            isp = d.str("isp"),
            connectionType = usage,
            note = d.str("lastReportedAt")?.let { "Last reported $it" },
        )
    }

    fun ipqs(body: String): SourceResult {
        val o = obj(body)
        if (o.bool("success") == false) return SourceResult.failed(SourceId.IPQS, o.str("message") ?: "failed")
        return SourceResult(
            SourceId.IPQS,
            proxy = o.bool("proxy"),
            vpn = o.bool("vpn"),
            tor = o.bool("tor"),
            score = o.int("fraud_score"),
            recentAbuse = o.bool("recent_abuse"),
            countryCode = o.str("country_code"),
            region = o.str("region"),
            city = o.str("city"),
            isp = o.str("ISP"),
            asn = o.int("ASN")?.let { "AS$it" },
            connectionType = o.str("connection_type"),
            note = if (o.bool("bot_status") == true) "Recent bot activity" else null,
        )
    }

    /** Accepts both the v3 (`{"scamalytics":{...}}`) and legacy flat response shapes. */
    fun scamalytics(body: String): SourceResult {
        val root = obj(body)
        val o = root.obj("scamalytics") ?: root
        val status = o.str("status")
        if (status != null && status != "ok") return SourceResult.failed(SourceId.SCAMALYTICS, o.str("error") ?: status)
        val score = o.int("scamalytics_score") ?: o.int("score")
        val risk = o.str("scamalytics_risk") ?: o.str("risk")
        if (score == null && risk == null) return SourceResult.failed(SourceId.SCAMALYTICS, o.str("error") ?: "no data")
        return SourceResult(SourceId.SCAMALYTICS, score = score, riskLabel = risk?.lowercase())
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(body: String): JsonObject = lenient.parseToJsonElement(body).jsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? =
        prim(key)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.let { p ->
        p.booleanOrNull ?: when (p.contentOrNull?.lowercase()) { "yes", "true", "1" -> true; "no", "false", "0" -> false; else -> null }
    }

    private fun JsonObject.int(key: String): Int? = prim(key)?.let { p -> p.contentOrNull?.toIntOrNull() ?: p.doubleOrNull?.toInt() }
}
