package dev.resproxy.reputation

import dev.resproxy.data.Verdict

/**
 * Turns per-source results into one verdict. "Detected as a proxy" is deliberately *not* a
 * negative signal on its own: residential proxy IPs are routinely flagged as such. What matters
 * is abuse history, blacklists, Tor, and whether the IP is really residential.
 */
object ReputationAggregator {

    fun aggregate(ip: String, results: List<SourceResult>, now: Long = System.currentTimeMillis()): ReputationReport {
        val ok = results.filter { it.ok }
        val reasons = mutableListOf<Reason>()
        fun bad(t: String) = reasons.add(Reason(Verdict.BAD, t))
        fun sus(t: String) = reasons.add(Reason(Verdict.SUSPICIOUS, t))

        for (r in ok) {
            val src = r.source.label
            if (r.tor == true) bad("Tor exit node ($src)")
            if (r.listed == true) bad("Listed on $src blacklist")
            if (r.abuser == true) bad("Flagged as abuser by $src")
            if (r.recentAbuse == true) bad("Recent abuse reported by $src")
            if (r.datacenter == true) sus("Datacenter/hosting IP, not residential ($src)")
            val score = r.score
            when (r.source) {
                SourceId.ABUSEIPDB -> if (score != null) {
                    val txt = "AbuseIPDB confidence $score%" + (r.reports?.let { " ($it reports)" } ?: "")
                    if (score >= 50) bad(txt) else if (score >= 1) sus(txt)
                }
                SourceId.IPQS -> if (score != null) {
                    if (score >= 85) bad("IPQS fraud score $score") else if (score >= 75) sus("IPQS fraud score $score")
                }
                SourceId.SCAMALYTICS -> when (r.riskLabel) {
                    "high", "very high" -> bad("Scamalytics risk ${r.riskLabel}" + (score?.let { " ($it)" } ?: ""))
                    "medium" -> sus("Scamalytics risk medium" + (score?.let { " ($it)" } ?: ""))
                }
                SourceId.PROXYCHECK -> if (score != null && score >= 67) sus("proxycheck.io risk $score")
                else -> Unit
            }
        }
        val vpnCount = ok.count { it.vpn == true }
        if (vpnCount >= 2) sus("Flagged as VPN by $vpnCount sources")

        val verdict = when {
            ok.isEmpty() -> Verdict.UNKNOWN
            else -> reasons.maxByOrNull { it.verdict.severity }?.verdict ?: Verdict.CLEAN
        }
        return ReputationReport(
            ip = ip,
            verdict = verdict,
            reasons = reasons.distinct().sortedByDescending { it.verdict.severity },
            results = results,
            geo = geo(ok),
            proxyFlags = ok.count { it.proxy == true },
            checkedAt = now,
        )
    }

    private val geoPriority = listOf(SourceId.IPAPI_IS, SourceId.IP_API, SourceId.PROXYCHECK, SourceId.IPQS, SourceId.ABUSEIPDB)

    private fun geo(ok: List<SourceResult>): GeoInfo? {
        val ordered = geoPriority.mapNotNull { id -> ok.firstOrNull { it.source == id } }
        if (ordered.isEmpty()) return null
        fun pick(f: (SourceResult) -> String?) = ordered.firstNotNullOfOrNull(f)
        return GeoInfo(
            country = pick { it.country },
            countryCode = pick { it.countryCode }?.uppercase(),
            region = pick { it.region },
            city = pick { it.city },
            isp = pick { it.isp },
            asn = pick { it.asn },
            connectionType = pick { it.connectionType },
        )
    }
}
