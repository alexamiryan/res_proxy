package dev.resproxy.reputation

import dev.resproxy.data.Verdict

enum class SourceId(val label: String) {
    IPAPI_IS("ipapi.is"),
    PROXYCHECK("proxycheck.io"),
    IP_API("ip-api.com"),
    ABUSEIPDB("AbuseIPDB"),
    IPQS("IPQualityScore"),
    SCAMALYTICS("Scamalytics"),
    DNSBL_SPAMHAUS("Spamhaus ZEN"),
    DNSBL_SPAMCOP("SpamCop"),
    DNSBL_BARRACUDA("Barracuda"),
    DNSBL_UCEPROTECT("UCEPROTECT L1"),
}

/** One source's answer, normalized. `null` means "this source doesn't report that signal". */
data class SourceResult(
    val source: SourceId,
    val ok: Boolean = true,
    val error: String? = null,
    val proxy: Boolean? = null,
    val vpn: Boolean? = null,
    val tor: Boolean? = null,
    val datacenter: Boolean? = null,
    val abuser: Boolean? = null,
    val listed: Boolean? = null,
    /** Source-native risk score: AbuseIPDB confidence, IPQS fraud score, proxycheck risk, Scamalytics score. */
    val score: Int? = null,
    /** Scamalytics risk label ("low", "medium", "high", "very high"). */
    val riskLabel: String? = null,
    val recentAbuse: Boolean? = null,
    val reports: Int? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val region: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val asn: String? = null,
    val connectionType: String? = null,
    val note: String? = null,
) {
    companion object {
        fun failed(source: SourceId, error: String) = SourceResult(source, ok = false, error = error)
    }
}

data class Reason(val verdict: Verdict, val text: String)

data class GeoInfo(
    val country: String?,
    val countryCode: String?,
    val region: String?,
    val city: String?,
    val isp: String?,
    val asn: String?,
    val connectionType: String?,
)

data class ReputationReport(
    val ip: String,
    val verdict: Verdict,
    val reasons: List<Reason>,
    val results: List<SourceResult>,
    val geo: GeoInfo?,
    val proxyFlags: Int,
    val checkedAt: Long,
)
