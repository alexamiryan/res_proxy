package dev.resproxy

import dev.resproxy.data.Verdict
import dev.resproxy.reputation.Dnsbl
import dev.resproxy.reputation.Parsers
import dev.resproxy.reputation.ReputationAggregator
import dev.resproxy.reputation.SourceId
import dev.resproxy.reputation.SourceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class ReputationTest {

    private val ip = "203.0.113.7"

    private val ipapiResidential = """
        {"ip":"203.0.113.7","is_bogon":false,"is_mobile":false,"is_datacenter":false,"is_tor":false,
         "is_proxy":true,"is_vpn":false,"is_abuser":false,
         "company":{"name":"Comcast Cable","abuser_score":"0.0012 (Low)","type":"isp"},
         "asn":{"asn":7922,"org":"Comcast"},
         "location":{"country":"United States","country_code":"US","state":"California","city":"San Jose"}}
    """.trimIndent()

    private val proxycheck = """{"status":"ok","203.0.113.7":{"asn":"AS7922","provider":"Comcast","country":"United States","isocode":"US","proxy":"yes","type":"Residential","risk":33}}"""
    private val ipApi = """{"status":"success","country":"United States","countryCode":"US","regionName":"California","city":"San Jose","isp":"Comcast","org":"Comcast","as":"AS7922 Comcast","mobile":false,"proxy":true,"hosting":false,"query":"203.0.113.7"}"""

    private fun clean() = listOf(
        Parsers.ipapiIs(ipapiResidential),
        Parsers.proxycheck(proxycheck, ip),
        Parsers.ipApi(ipApi),
        SourceResult(SourceId.DNSBL_SPAMCOP, listed = false),
    )

    @Test fun residentialProxyFlagsAloneAreClean() {
        val r = ReputationAggregator.aggregate(ip, clean())
        assertEquals(Verdict.CLEAN, r.verdict)
        assertEquals(3, r.proxyFlags)
        assertEquals("US", r.geo?.countryCode)
        assertEquals("San Jose", r.geo?.city)
        assertEquals("AS7922", r.geo?.asn)
    }

    @Test fun abuseIpDbScores() {
        val low = Parsers.abuseIpDb("""{"data":{"ipAddress":"$ip","abuseConfidenceScore":12,"totalReports":3,"usageType":"Fixed Line ISP","isTor":false}}""")
        assertEquals(Verdict.SUSPICIOUS, ReputationAggregator.aggregate(ip, clean() + low).verdict)
        val high = Parsers.abuseIpDb("""{"data":{"abuseConfidenceScore":87,"totalReports":40,"usageType":"Data Center/Web Hosting/Transit"}}""")
        val r = ReputationAggregator.aggregate(ip, clean() + high)
        assertEquals(Verdict.BAD, r.verdict)
        assertTrue(r.reasons.any { it.text.contains("Datacenter") })
    }

    @Test fun ipqsThresholds() {
        fun v(score: Int, abuse: Boolean = false) = ReputationAggregator.aggregate(
            ip, clean() + Parsers.ipqs("""{"success":true,"fraud_score":$score,"proxy":true,"vpn":false,"tor":false,"recent_abuse":$abuse,"bot_status":false,"ISP":"Comcast","ASN":7922}""")
        ).verdict
        assertEquals(Verdict.CLEAN, v(60))
        assertEquals(Verdict.SUSPICIOUS, v(78))
        assertEquals(Verdict.BAD, v(90))
        assertEquals(Verdict.BAD, v(10, abuse = true))
    }

    @Test fun scamalyticsBothShapes() {
        val v3 = Parsers.scamalytics("""{"scamalytics":{"status":"ok","scamalytics_score":72,"scamalytics_risk":"high"}}""")
        assertEquals("high", v3.riskLabel)
        assertEquals(Verdict.BAD, ReputationAggregator.aggregate(ip, clean() + v3).verdict)
        val legacy = Parsers.scamalytics("""{"score":"40","risk":"medium","ip":"$ip"}""")
        assertEquals(40, legacy.score)
        assertEquals(Verdict.SUSPICIOUS, ReputationAggregator.aggregate(ip, clean() + legacy).verdict)
    }

    @Test fun torAndAbuserAreBad() {
        val tor = Parsers.ipapiIs(ipapiResidential.replace("\"is_tor\":false", "\"is_tor\":true"))
        assertEquals(Verdict.BAD, ReputationAggregator.aggregate(ip, listOf(tor)).verdict)
        val abuser = Parsers.ipapiIs(ipapiResidential.replace("\"is_abuser\":false", "\"is_abuser\":true"))
        assertEquals(Verdict.BAD, ReputationAggregator.aggregate(ip, listOf(abuser)).verdict)
    }

    @Test fun twoVpnFlagsAreSuspicious() {
        val a = SourceResult(SourceId.IPAPI_IS, vpn = true)
        val b = SourceResult(SourceId.IPQS, vpn = true)
        assertEquals(Verdict.CLEAN, ReputationAggregator.aggregate(ip, listOf(a)).verdict)
        assertEquals(Verdict.SUSPICIOUS, ReputationAggregator.aggregate(ip, listOf(a, b)).verdict)
    }

    @Test fun allFailedIsUnknown() {
        val r = ReputationAggregator.aggregate(ip, listOf(SourceResult.failed(SourceId.IPAPI_IS, "timeout")))
        assertEquals(Verdict.UNKNOWN, r.verdict)
    }

    @Test fun parserErrors() {
        assertFalse(Parsers.ipApi("""{"status":"fail","message":"private range"}""").ok)
        assertFalse(Parsers.proxycheck("""{"status":"denied","message":"limit reached"}""", ip).ok)
        assertFalse(Parsers.abuseIpDb("""{"errors":[{"detail":"auth failed"}]}""").ok)
        assertFalse(Parsers.ipqs("""{"success":false,"message":"bad key"}""").ok)
    }

    @Test fun dnsbl() {
        val pbl = Dnsbl.interpret(SourceId.DNSBL_SPAMHAUS, listOf("127.0.0.11"))
        assertEquals(false, pbl.listed)
        assertTrue(pbl.note!!.contains("PBL"))
        val xbl = Dnsbl.interpret(SourceId.DNSBL_SPAMHAUS, listOf("127.0.0.4", "127.0.0.10"))
        assertEquals(true, xbl.listed)
        assertFalse(Dnsbl.interpret(SourceId.DNSBL_SPAMHAUS, listOf("127.255.255.254")).ok)
        assertEquals(true, Dnsbl.interpret(SourceId.DNSBL_SPAMCOP, listOf("127.0.0.2")).listed)

        var queried = ""
        val miss = Dnsbl.lookup(SourceId.DNSBL_SPAMCOP, "bl.spamcop.net", "1.2.3.4") { queried = it; throw UnknownHostException() }
        assertEquals("4.3.2.1.bl.spamcop.net", queried)
        assertEquals(false, miss.listed)
        assertFalse(Dnsbl.lookup(SourceId.DNSBL_SPAMCOP, "bl.spamcop.net", "2001:db8::1").ok)
        assertEquals(Verdict.BAD, ReputationAggregator.aggregate(ip, clean() + xbl).verdict)
    }
}
