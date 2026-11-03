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

/** Provider login. [apiToken] (IPRoyal dashboard → Settings → API) is only used to fetch location lists. */
@Serializable
data class Account(
    val provider: Provider = Provider.IPROYAL,
    val username: String = "",
    val password: String = "",
    val apiToken: String = "",
)

/** Target location as provider codes; names are kept for display. Empty code = any. */
@Serializable
data class Location(
    val country: String = "",
    val countryName: String = "",
    val state: String = "",
    val stateName: String = "",
    val city: String = "",
    val cityName: String = "",
) {
    val label: String
        get() = listOf(cityName, stateName, countryName).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "Any location" }
}

/** Technical options, all in Settings → Advanced. */
@Serializable
data class ConnectionSettings(
    val protocol: Protocol = Protocol.SOCKS5,
    val sticky: Boolean = true,
    val lifetimeMinutes: Int = 1440,
    val hostOverride: String = "",
    val portOverride: Int = 0,
    val tunnelMode: TunnelMode = TunnelMode.FULL,
    val splitMode: SplitMode = SplitMode.ALL,
    val apps: Set<String> = emptySet(),
)

/**
 * Everything the tunnel needs, derived from [Account] + [AppSettings] at connect time.
 * [host] / [port] left empty / 0 fall back to the provider preset defaults.
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
) {
    companion object {
        fun from(account: Account, s: AppSettings, sessionId: String) = Profile(
            id = account.provider.name,
            name = account.provider.label,
            provider = account.provider,
            protocol = s.connection.protocol,
            host = s.connection.hostOverride,
            port = s.connection.portOverride,
            username = account.username,
            password = account.password,
            country = s.location.country,
            state = s.location.state,
            city = s.location.city,
            sticky = s.connection.sticky,
            lifetimeMinutes = s.connection.lifetimeMinutes,
            sessionId = sessionId,
            tunnelMode = s.connection.tunnelMode,
            splitMode = s.connection.splitMode,
            apps = s.connection.apps,
        )
    }
}

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
    val schema: Int = 0,
    val provider: Provider = Provider.IPROYAL,
    val location: Location = Location(),
    val sessionId: String = "",
    val connection: ConnectionSettings = ConnectionSettings(),
    /** Keep the TUN up (traffic blocked) while connecting or when the proxy fails. */
    val killSwitch: Boolean = true,
    /** Reconnect on app start / boot if the user didn't disconnect. */
    val autoConnect: Boolean = true,
    val wasConnected: Boolean = false,
    val recheckOnConnect: Boolean = true,
    val autoRotate: Boolean = true,
    val rotateThreshold: Verdict = Verdict.SUSPICIOUS,
    val maxRotations: Int = 10,
    val healthIntervalSec: Int = 30,
) {
    companion object {
        const val SCHEMA = 2
    }
}

/** Cumulative bytes per provider, for providers that bill per GB. */
@Serializable
data class UsageTotals(val rxBytes: Long = 0, val txBytes: Long = 0)
