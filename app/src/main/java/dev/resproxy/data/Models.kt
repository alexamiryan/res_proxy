package dev.resproxy.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Provider(val label: String) {
    IPROYAL("IPRoyal"),
    GEONODE("Geonode"),
    GENERIC("Custom"),
}

@Serializable
enum class Protocol(val label: String) { SOCKS5("SOCKS5"), HTTP("HTTP") }

@Serializable
enum class TunnelMode { FULL, PROXY_ONLY }

@Serializable
enum class SplitMode { ALL, ONLY_SELECTED, EXCEPT_SELECTED }

/**
 * A saved proxy profile. [host] / [port] left empty / 0 fall back to the provider preset defaults.
 * [password] is plain text in memory only; [ProfileRepository] encrypts it at rest.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New profile",
    val provider: Provider = Provider.IPROYAL,
    val protocol: Protocol = Protocol.SOCKS5,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val country: String = "",
    val state: String = "",
    val city: String = "",
    val sticky: Boolean = true,
    val lifetimeMinutes: Int = 30,
    val sessionId: String = "",
    val tunnelMode: TunnelMode = TunnelMode.FULL,
    val splitMode: SplitMode = SplitMode.ALL,
    val apps: Set<String> = emptySet(),
)

@Serializable
enum class Verdict(val severity: Int) { UNKNOWN(-1), CLEAN(0), SUSPICIOUS(1), BAD(2) }

@Serializable
data class ApiKeys(
    val abuseIpDb: String = "",
    val ipqs: String = "",
    val proxycheck: String = "",
    val ipapiIs: String = "",
    val scamalyticsUser: String = "",
    val scamalyticsKey: String = "",
)

@Serializable
data class AppSettings(
    val selectedProfileId: String? = null,
    val autoConnectOnBoot: Boolean = false,
    val recheckOnConnect: Boolean = true,
    val autoRotate: Boolean = false,
    val rotateThreshold: Verdict = Verdict.BAD,
    val maxRotations: Int = 5,
    val healthIntervalSec: Int = 30,
)

/** Cumulative bytes per profile, for providers that bill per GB. */
@Serializable
data class UsageTotals(val rxBytes: Long = 0, val txBytes: Long = 0)
