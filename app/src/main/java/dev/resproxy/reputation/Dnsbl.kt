package dev.resproxy.reputation

import java.net.InetAddress
import java.net.UnknownHostException

/** Reversed-octet DNS blacklist lookups (IPv4 only). */
object Dnsbl {

    val zones = listOf(
        SourceId.DNSBL_SPAMHAUS to "zen.spamhaus.org",
        SourceId.DNSBL_SPAMCOP to "bl.spamcop.net",
        SourceId.DNSBL_BARRACUDA to "b.barracudacentral.org",
        SourceId.DNSBL_UCEPROTECT to "dnsbl-1.uceprotect.net",
    )

    fun lookup(source: SourceId, zone: String, ip: String, resolve: (String) -> List<String> = ::resolveA): SourceResult {
        val octets = ip.split('.')
        if (octets.size != 4) return SourceResult.failed(source, "IPv6 not supported")
        val name = octets.reversed().joinToString(".") + "." + zone
        val answers = try {
            resolve(name)
        } catch (e: UnknownHostException) {
            return SourceResult(source, listed = false)
        } catch (e: Exception) {
            return SourceResult.failed(source, e.message ?: "lookup failed")
        }
        return interpret(source, answers)
    }

    internal fun interpret(source: SourceId, answers: List<String>): SourceResult {
        if (answers.isEmpty()) return SourceResult(source, listed = false)
        // 127.255.255.x: Spamhaus refusing the query (e.g. via a public resolver) — not a listing.
        if (answers.all { it.startsWith("127.255.255.") }) {
            return SourceResult.failed(source, "Query refused by blacklist (resolver blocked)")
        }
        if (source == SourceId.DNSBL_SPAMHAUS) {
            val codes = answers.mapNotNull { it.substringAfterLast('.').toIntOrNull() }
            val abuse = codes.filter { it in 2..9 }
            val pbl = codes.any { it == 10 || it == 11 }
            if (abuse.isEmpty()) {
                return SourceResult(
                    source, listed = false,
                    note = if (pbl) "In PBL (end-user/residential range) — expected for residential IPs" else null,
                )
            }
            val lists = abuse.map { if (it == 2 || it == 3 || it == 9) "SBL" else "XBL" }.distinct()
            return SourceResult(source, listed = true, note = "Listed in ${lists.joinToString("/")}")
        }
        return SourceResult(source, listed = answers.any { it.startsWith("127.") }, note = answers.joinToString())
    }

    private fun resolveA(name: String): List<String> =
        InetAddress.getAllByName(name).mapNotNull { it.hostAddress }
}
