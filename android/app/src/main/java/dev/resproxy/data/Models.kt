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
 * Provider login. The API fields are only used to fetch location lists: IPRoyal wants a dashboard
 * API token ([apiToken]); Geonode wants the dashboard login ([apiLogin] / [apiPassword]).
 */
@Serializable
data class Account(
    val provider: Provider = Provider.IPROYAL,
    val username: String = "",
    val password: String = "",
    val apiToken: String = "",
    val apiLogin: String = "",
    val apiPassword: String = "",
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

@Serializable
data class Gateway(val host: String = "", val port: Int = 0)

const val MAX_LIFETIME_MINUTES = 7 * 1440

/** Technical options, all in Settings → Advanced. */
@Serializable
data class ConnectionSettings(
    val protocol: Protocol = Protocol.SOCKS5,
    val sticky: Boolean = true,
    /** Max allowed: IPRoyal 7 days; Geonode is capped to 24 h by its preset. */
    val lifetimeMinutes: Int = MAX_LIFETIME_MINUTES,
    /** Per-provider gateway override; empty host / 0 port = preset default. */
    val gateways: Map<Provider, Gateway> = emptyMap(),
    val tunnelMode: TunnelMode = TunnelMode.FULL,
    val splitMode: SplitMode = SplitMode.ALL,
    val apps: Set<String> = emptySet(),
    /** Chrome extension's split tunnelling by site (domains). Unused on Android; kept so backups round-trip. */
    val siteSplitMode: SplitMode = SplitMode.ALL,
    val sites: Set<String> = emptySet(),
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
            host = s.connection.gateways[account.provider]?.host.orEmpty(),
            port = s.connection.gateways[account.provider]?.port ?: 0,
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
    /** Last location used with each provider, restored when switching back. */
    val savedLocations: Map<Provider, Location> = emptyMap(),
    /** Route the upstream proxy connection through the VPN selected in [vpnExit]. */
    val chainEnabled: Boolean = false,
    /** Which VPN to chain through: `wg:<VpnProfile.id>` or `mullvad:<country | country-city | relay hostname>`. */
    val vpnExit: String = "",
    /** Keep local-network traffic (printers, Chromecast, router UI) off the VPN. */
    val bypassLan: Boolean = true,
    /**
     * Sticky session last used for each provider + location ([sessionKey]), so switching back keeps the same
     * exit IP until the session's lifetime runs out. "New IP" replaces the entry.
     */
    val sessions: Map<String, SavedSession> = emptyMap(),
) {
    /** Lifetime the provider actually applies (Geonode caps sticky sessions at 24 h). */
    val effectiveLifetime: Int get() = connection.lifetimeMinutes.coerceIn(1, if (provider == Provider.GEONODE) 1440 else MAX_LIFETIME_MINUTES)

    val sessionKey: String get() = listOf(provider.name, location.country, location.state, location.city).joinToString("|")

    /** Sets [sessionId] and remembers it for the current provider + location. */
    fun withSession(id: String, now: Long = System.currentTimeMillis()): AppSettings {
        val same = sessions[sessionKey]?.takeIf { it.id == id && !it.expired(now) }
        val kept = sessions.filterValues { !it.expired(now) } - sessionKey
        val entry = same ?: SavedSession(id, now, effectiveLifetime)
        return copy(sessionId = id, sessions = if (id.isEmpty()) kept else kept + (sessionKey to entry))
    }

    /** Picks up the remembered session for the current provider + location, or none (a new one gets made). */
    fun restoreSession(now: Long = System.currentTimeMillis()): AppSettings =
        copy(sessionId = sessions[sessionKey]?.takeIf { !it.expired(now) }?.id.orEmpty())

    companion object {
        const val SCHEMA = 3
    }
}

@Serializable
data class SavedSession(val id: String, val createdAt: Long, val lifetimeMinutes: Int) {
    /** A minute of margin so we never reuse a session the provider is about to drop. */
    fun expired(now: Long) = now >= createdAt + (lifetimeMinutes - 1) * 60_000L
}

/** Legacy single imported WireGuard config (backups made before VPN profiles). */
@Serializable
data class VpnChain(val name: String = "", val config: String = "")

/** An imported WireGuard `.conf`; stored encrypted (it contains the private key). */
@Serializable
data class VpnProfile(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val config: String = "",
)

/** This install's WireGuard key registered on the Mullvad account (per install, never synced). */
@Serializable
data class MullvadDevice(
    val id: String = "",
    val name: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val ipv4: String = "",
    val ipv6: String = "",
)

@Serializable
data class MullvadAccount(val number: String = "", val expiry: String = "", val device: MullvadDevice? = null)

/** Parsed [AppSettings.vpnExit]. */
sealed interface VpnExit {
    data class Imported(val id: String) : VpnExit
    /** [code]: `se`, `se-got` or a relay hostname like `se-got-wg-001`. */
    data class Mullvad(val code: String) : VpnExit

    companion object {
        fun parse(s: String): VpnExit? = when {
            s.startsWith("wg:") -> Imported(s.removePrefix("wg:"))
            s.startsWith("mullvad:") -> Mullvad(s.removePrefix("mullvad:"))
            else -> null
        }
        fun imported(id: String) = "wg:$id"
        fun mullvad(code: String) = "mullvad:$code"
    }
}

/** Cumulative bytes per provider, for providers that bill per GB. */
@Serializable
data class UsageTotals(val rxBytes: Long = 0, val txBytes: Long = 0)
